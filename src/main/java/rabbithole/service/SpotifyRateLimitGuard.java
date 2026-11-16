package rabbithole.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Spotify limits how many requests each app can make, and when it answers 429 it says how long to
 * wait. Every Spotify Web API request goes through {@link #filter()}, so once Spotify asks for a
 * pause nothing more is sent until it ends. Requests sent during a pause can make Spotify lengthen
 * it. The pause is saved in the database, so a restart or redeploy doesn't forget it.
 *
 * <p>Spotify doesn't publish its numbers. Its short-term limit uses a rolling 30-second window, and
 * apps in Development Mode also have a separate quota that, once used up, blocks the app for most of a
 * day. So the guard also keeps RabbitHole under its own daily allowance, counted per hour over the
 * last 24 hours and saved so deploys don't reset it.
 */
@Service
public class SpotifyRateLimitGuard {

    private static final Logger log = LoggerFactory.getLogger(SpotifyRateLimitGuard.class);

    private static final long DEFAULT_PAUSE_SECONDS = 30;
    private static final long HOUR_MS = Duration.ofHours(1).toMillis();
    private static final long DAY_MS = Duration.ofDays(1).toMillis();
    private static final String USAGE_PREFIX = "spotify-hour:";

    /** Reactor context key for who a request is for: "user:<id>" when connected, otherwise "ip:<address>". */
    public static final String VISITOR_CONTEXT_KEY = "rabbithole.visitor";

    // Connected accounts were approved by the app owner in Spotify's dashboard, so they're trusted and
    // keep this share for themselves even if everyone else's part is used up.
    private static final double RESERVED_FOR_CONNECTED_ACCOUNTS = 0.2;
    // No single logged-out visitor can use more than this share of the day's allowance.
    private static final double MAX_SHARE_PER_VISITOR = 0.15;

    private final DatabaseClient databaseClient;
    private final UsageCounterStore usageStore;
    private final int dailyRequestLimit;

    // Spotify requests made in each hour (keyed by the hour's start, epoch ms) over the last 24 hours.
    private final NavigableMap<Long, Integer> requestsPerHour = new TreeMap<>();

    // The same, per logged-out visitor. Kept in memory only: a deploy resets it, but the shared total
    // above is saved, so that can't be used to get around the daily allowance.
    private final Cache<String, NavigableMap<Long, Integer>> requestsPerVisitorHour = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofDays(1))
            .build();

    private volatile long pausedUntilMs = 0;

    public SpotifyRateLimitGuard(
            DatabaseClient databaseClient,
            UsageCounterStore usageStore,
            @Value("${SPOTIFY_DAILY_REQUEST_LIMIT:1000}") int dailyRequestLimit
    ) {
        this.databaseClient = databaseClient;
        this.usageStore = usageStore;
        this.dailyRequestLimit = dailyRequestLimit;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadSavedUsage() {
        long now = System.currentTimeMillis();

        synchronized (this) {
            for (UsageCounterStore.SavedCount saved : usageStore.load(USAGE_PREFIX)) {
                if (saved.windowStart() + DAY_MS > now) {
                    requestsPerHour.merge(saved.windowStart(), saved.requestCount(), Math::max);
                }
            }

            log.info("Spotify requests in the last 24 hours: {} of {} allowed.", usedInLastDay(now), dailyRequestLimit);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadSavedPause() {
        try {
            Long saved = databaseClient.sql("SELECT paused_until AS \"pausedUntil\" FROM spotify_pause WHERE id = 1")
                    .map(row -> row.get("pausedUntil", Long.class))
                    .one()
                    .block(Duration.ofSeconds(10));

            if (saved != null && saved > System.currentTimeMillis()) {
                pausedUntilMs = saved;
                log.warn("Spotify asked RabbitHole to pause before the restart; still paused until {}.", Instant.ofEpochMilli(saved));
            }
        } catch (RuntimeException error) {
            log.warn("Could not load a saved Spotify pause; continuing without one.", error);
        }
    }

    /** True while Spotify itself has asked RabbitHole to wait. */
    public boolean isPaused() {
        return System.currentTimeMillis() < pausedUntilMs;
    }

    /**
     * When this kind of visitor can make Spotify requests again (epoch ms), or 0 when they can now.
     * Connected accounts have a reserved part of the allowance, so they can be blocked later than others.
     */
    public long blockedUntilMs(boolean connectedAccount) {
        long spotifyPause = isPaused() ? pausedUntilMs : 0;

        return Math.max(spotifyPause, dailyLimitResetsAtMs(connectedAccount));
    }

    /** "spotify" when Spotify asked for the wait, "daily-limit" when RabbitHole's own allowance ran out. */
    public String blockReason(boolean connectedAccount) {
        if (isPaused()) {
            return "spotify";
        }

        return dailyLimitResetsAtMs(connectedAccount) > 0 ? "daily-limit" : "";
    }

    public ResponseStatusException pausedError() {
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Spotify has paused requests from RabbitHole for " + describeWait(pausedUntilMs) + ". Try again after that."
        );
    }

    public ResponseStatusException dailyLimitError(boolean connectedAccount) {
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RabbitHole has used today's Spotify allowance, which keeps it within Spotify's limits. "
                        + "Try again in " + describeWait(dailyLimitResetsAtMs(connectedAccount)) + "."
        );
    }

    public ResponseStatusException visitorLimitError(String visitor) {
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "You've used your share of RabbitHole's Spotify allowance for today. "
                        + "Try again in " + describeWait(visitorLimitResetsAtMs(visitor)) + "."
        );
    }

    /** Counts one Spotify request for this visitor (null when unknown), or says why it's refused. */
    synchronized Allowance tryUseDailyAllowance(String visitor) {
        long now = System.currentTimeMillis();
        boolean connectedAccount = isConnectedAccount(visitor);

        if (dailyRequestLimit > 0) {
            if (usedInLastDay(now) >= limitFor(connectedAccount)) {
                return Allowance.DAILY_LIMIT;
            }

            if (isAnonymousVisitor(visitor) && used(visitorHours(visitor), now) >= perVisitorLimit()) {
                return Allowance.VISITOR_LIMIT;
            }
        }

        long hour = now - now % HOUR_MS;
        boolean newHour = !requestsPerHour.containsKey(hour);
        int count = requestsPerHour.merge(hour, 1, Integer::sum);

        if (isAnonymousVisitor(visitor)) {
            visitorHours(visitor).merge(hour, 1, Integer::sum);
        }

        usageStore.save(USAGE_PREFIX + hour, hour, count);

        if (newHour) {
            usageStore.deleteOlderThan(USAGE_PREFIX, now - DAY_MS - HOUR_MS);
        }

        return Allowance.ALLOWED;
    }

    /** When there's room again in this visitor type's part of the allowance (epoch ms), or 0 if there is now. */
    synchronized long dailyLimitResetsAtMs(boolean connectedAccount) {
        if (dailyRequestLimit <= 0) {
            return 0;
        }

        return resetsAt(requestsPerHour, limitFor(connectedAccount));
    }

    synchronized long visitorLimitResetsAtMs(String visitor) {
        if (dailyRequestLimit <= 0 || !isAnonymousVisitor(visitor)) {
            return 0;
        }

        return resetsAt(visitorHours(visitor), perVisitorLimit());
    }

    /** Everyone except connected accounts shares what isn't reserved for them. */
    private int limitFor(boolean connectedAccount) {
        return connectedAccount
                ? dailyRequestLimit
                : (int) Math.floor(dailyRequestLimit * (1 - RESERVED_FOR_CONNECTED_ACCOUNTS));
    }

    private int perVisitorLimit() {
        return Math.max(1, (int) Math.floor(dailyRequestLimit * MAX_SHARE_PER_VISITOR));
    }

    // Callers hold the lock.
    private NavigableMap<Long, Integer> visitorHours(String visitor) {
        return requestsPerVisitorHour.get(visitor, ignored -> new TreeMap<>());
    }

    private static boolean isConnectedAccount(String visitor) {
        return visitor != null && visitor.startsWith("user:");
    }

    /**
     * A logged-out visitor, who is also held to a personal share. Requests with no known visitor (such
     * as finishing a login) aren't, but like logged-out visitors they only use the shared part, so
     * repeated logins with unapproved accounts can't eat into what's reserved for connected accounts.
     */
    private static boolean isAnonymousVisitor(String visitor) {
        return visitor != null && !isConnectedAccount(visitor);
    }

    /** When enough of these hours' requests age out to bring them under the limit, or 0 if they're under. */
    private static long resetsAt(NavigableMap<Long, Integer> hours, int limit) {
        long now = System.currentTimeMillis();
        int remaining = used(hours, now);

        if (remaining < limit) {
            return 0;
        }

        for (Map.Entry<Long, Integer> hour : hours.entrySet()) {
            remaining -= hour.getValue();

            if (remaining < limit) {
                return hour.getKey() + DAY_MS;
            }
        }

        return now + DAY_MS;
    }

    // Callers hold the lock.
    private int usedInLastDay(long now) {
        return used(requestsPerHour, now);
    }

    private static int used(NavigableMap<Long, Integer> hours, long now) {
        hours.headMap(now - DAY_MS, true).clear();

        return hours.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Refuses to send requests during a pause or once the allowance is used, and starts a pause when
     * Spotify answers 429. The rate-limit filter puts the visitor in the request's context.
     */
    public ExchangeFilterFunction filter() {
        return (request, next) -> Mono.deferContextual(context -> {
            if (isPaused()) {
                return Mono.error(pausedError());
            }

            String visitor = context.getOrDefault(VISITOR_CONTEXT_KEY, null);

            switch (tryUseDailyAllowance(visitor)) {
                case DAILY_LIMIT -> {
                    log.warn("Daily Spotify allowance used; refusing {} {} for {}.", request.method(), request.url().getPath(), visitor);
                    return Mono.error(dailyLimitError(isConnectedAccount(visitor)));
                }
                case VISITOR_LIMIT -> {
                    log.warn("Visitor used their share of the daily Spotify allowance; refusing {} {} for {}.", request.method(), request.url().getPath(), visitor);
                    return Mono.error(visitorLimitError(visitor));
                }
                case ALLOWED -> {
                }
            }

            return next.exchange(request).doOnNext(response -> {
                if (response.statusCode().value() == 429) {
                    pauseFor(retryAfterSeconds(response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
                }
            });
        });
    }

    enum Allowance {
        ALLOWED,
        DAILY_LIMIT,
        VISITOR_LIMIT
    }

    void pauseFor(long seconds) {
        long until = System.currentTimeMillis() + Math.max(1, seconds) * 1000;

        if (until <= pausedUntilMs) {
            return;
        }

        pausedUntilMs = until;
        log.warn("Spotify asked RabbitHole to pause for {} seconds; no Spotify requests until {}.", seconds, Instant.ofEpochMilli(until));

        save(until).subscribe(
                ignored -> {
                },
                error -> log.warn("Could not save the Spotify pause.", error)
        );
    }

    private Mono<Void> save(long until) {
        return databaseClient.sql("UPDATE spotify_pause SET paused_until = :until WHERE id = 1")
                .bind("until", until)
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows > 0
                        ? Mono.<Long>empty()
                        : databaseClient.sql("INSERT INTO spotify_pause (id, paused_until) VALUES (1, :until)")
                                .bind("until", until)
                                .fetch()
                                .rowsUpdated())
                .then();
    }

    private static String describeWait(long untilMs) {
        long seconds = Math.max(1, (untilMs - System.currentTimeMillis()) / 1000);

        if (seconds < 90) {
            return "about a minute";
        }

        long minutes = Math.round(seconds / 60.0);

        if (minutes < 90) {
            return "about " + minutes + " minutes";
        }

        return "about " + Math.round(minutes / 60.0) + " hours";
    }

    private long retryAfterSeconds(String header) {
        if (header == null || header.isBlank()) {
            return DEFAULT_PAUSE_SECONDS;
        }

        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException error) {
            return DEFAULT_PAUSE_SECONDS;
        }
    }
}
