package rabbithole.service;

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

    private final DatabaseClient databaseClient;
    private final UsageCounterStore usageStore;
    private final int dailyRequestLimit;

    // Spotify requests made in each hour (keyed by the hour's start, epoch ms) over the last 24 hours.
    private final NavigableMap<Long, Integer> requestsPerHour = new TreeMap<>();

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

    /** When Spotify requests can be made again (epoch ms), for either reason, or 0 when they can now. */
    public long blockedUntilMs() {
        long spotifyPause = isPaused() ? pausedUntilMs : 0;

        return Math.max(spotifyPause, dailyLimitResetsAtMs());
    }

    /** "spotify" when Spotify asked for the wait, "daily-limit" when RabbitHole's own allowance ran out. */
    public String blockReason() {
        if (isPaused()) {
            return "spotify";
        }

        return dailyLimitResetsAtMs() > 0 ? "daily-limit" : "";
    }

    public ResponseStatusException pausedError() {
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Spotify has paused requests from RabbitHole for " + describeWait(pausedUntilMs) + ". Try again after that."
        );
    }

    public ResponseStatusException dailyLimitError() {
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RabbitHole has used today's Spotify allowance, which keeps it within Spotify's limits. "
                        + "Try again in " + describeWait(dailyLimitResetsAtMs()) + "."
        );
    }

    /**
     * Counts one Spotify request against the daily allowance, or returns false when it's used up.
     * Package-private for tests.
     */
    synchronized boolean tryUseDailyAllowance() {
        long now = System.currentTimeMillis();

        if (dailyRequestLimit > 0 && usedInLastDay(now) >= dailyRequestLimit) {
            return false;
        }

        long hour = now - now % HOUR_MS;
        boolean newHour = !requestsPerHour.containsKey(hour);
        int count = requestsPerHour.merge(hour, 1, Integer::sum);

        usageStore.save(USAGE_PREFIX + hour, hour, count);

        if (newHour) {
            usageStore.deleteOlderThan(USAGE_PREFIX, now - DAY_MS - HOUR_MS);
        }

        return true;
    }

    /** When enough of the last day's requests age out to allow more (epoch ms), or 0 if there's room now. */
    synchronized long dailyLimitResetsAtMs() {
        long now = System.currentTimeMillis();
        int remaining = usedInLastDay(now);

        if (dailyRequestLimit <= 0 || remaining < dailyRequestLimit) {
            return 0;
        }

        for (Map.Entry<Long, Integer> hour : requestsPerHour.entrySet()) {
            remaining -= hour.getValue();

            if (remaining < dailyRequestLimit) {
                return hour.getKey() + DAY_MS;
            }
        }

        return now + DAY_MS;
    }

    // Callers hold the lock.
    private int usedInLastDay(long now) {
        requestsPerHour.headMap(now - DAY_MS, true).clear();

        return requestsPerHour.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Refuses to send requests during a pause or once the daily allowance is used, and starts a pause
     * when Spotify answers 429.
     */
    public ExchangeFilterFunction filter() {
        return (request, next) -> {
            if (isPaused()) {
                return Mono.error(pausedError());
            }

            if (!tryUseDailyAllowance()) {
                log.warn("Daily Spotify allowance of {} requests used; refusing {} {}.", dailyRequestLimit, request.method(), request.url().getPath());
                return Mono.error(dailyLimitError());
            }

            return next.exchange(request).doOnNext(response -> {
                if (response.statusCode().value() == 429) {
                    pauseFor(retryAfterSeconds(response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
                }
            });
        };
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
