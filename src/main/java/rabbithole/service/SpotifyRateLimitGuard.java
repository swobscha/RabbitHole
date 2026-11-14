package rabbithole.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Spotify limits how many requests each app can make, and when it answers 429 it says how long to
 * wait. Every Spotify Web API request goes through {@link #filter()}, so once Spotify asks for a
 * pause nothing more is sent until it ends. Requests sent during a pause can make Spotify lengthen
 * it. The pause is saved in the database, so a restart or redeploy doesn't forget it.
 */
@Service
public class SpotifyRateLimitGuard {

    private static final Logger log = LoggerFactory.getLogger(SpotifyRateLimitGuard.class);

    private static final long DEFAULT_PAUSE_SECONDS = 30;

    private final DatabaseClient databaseClient;

    private volatile long pausedUntilMs = 0;

    public SpotifyRateLimitGuard(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
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

    public boolean isPaused() {
        return System.currentTimeMillis() < pausedUntilMs;
    }

    /** When the current pause ends, in epoch milliseconds, or 0 when Spotify isn't paused. */
    public long pausedUntilMs() {
        return isPaused() ? pausedUntilMs : 0;
    }

    public ResponseStatusException pausedError() {
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Spotify has paused requests from RabbitHole for " + describeWait() + ". Try again after that."
        );
    }

    /** Refuses to send requests during a pause, and starts one when Spotify answers 429. */
    public ExchangeFilterFunction filter() {
        return (request, next) -> {
            if (isPaused()) {
                return Mono.error(pausedError());
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

    private String describeWait() {
        long seconds = Math.max(1, (pausedUntilMs - System.currentTimeMillis()) / 1000);

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
