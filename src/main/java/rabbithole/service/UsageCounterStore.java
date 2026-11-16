package rabbithole.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Saves usage counts (the daily Spotify allowance and the site-wide hourly limits) so a restart or
 * redeploy doesn't reset them. Counting happens in memory; this only keeps a copy. Saves run in the
 * background, and a failed save only means a count could restart lower after the next deploy.
 */
@Service
public class UsageCounterStore {

    private static final Logger log = LoggerFactory.getLogger(UsageCounterStore.class);

    private final DatabaseClient databaseClient;

    public UsageCounterStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /** Every saved counter whose name starts with the prefix. Only called at startup. */
    public List<SavedCount> load(String prefix) {
        try {
            List<SavedCount> counts = databaseClient.sql("""
                            SELECT name, window_start AS "windowStart", request_count AS "requestCount"
                            FROM usage_counters
                            WHERE name LIKE :prefix
                            """)
                    .bind("prefix", prefix + "%")
                    .map(row -> new SavedCount(
                            row.get("name", String.class),
                            row.get("windowStart", Long.class),
                            row.get("requestCount", Integer.class)
                    ))
                    .all()
                    .collectList()
                    .block(Duration.ofSeconds(10));

            return counts == null ? List.of() : counts;
        } catch (RuntimeException error) {
            log.warn("Could not load saved usage counts for {}; starting from zero.", prefix, error);
            return List.of();
        }
    }

    /**
     * Saves a counter's latest value. Saves can finish out of order, so an older, lower count never
     * replaces a newer one.
     */
    public void save(String name, long windowStart, int requestCount) {
        databaseClient.sql("""
                        UPDATE usage_counters
                        SET window_start = :windowStart, request_count = :requestCount
                        WHERE name = :name
                          AND (window_start < :windowStart
                               OR (window_start = :windowStart AND request_count < :requestCount))
                        """)
                .bind("name", name)
                .bind("windowStart", windowStart)
                .bind("requestCount", requestCount)
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows > 0
                        ? Mono.<Long>empty()
                        // No row was updated: either there's none yet, or the saved one is already newer.
                        // In the second case the insert fails on the duplicate name, which is fine.
                        : databaseClient.sql("""
                                        INSERT INTO usage_counters (name, window_start, request_count)
                                        VALUES (:name, :windowStart, :requestCount)
                                        """)
                                .bind("name", name)
                                .bind("windowStart", windowStart)
                                .bind("requestCount", requestCount)
                                .fetch()
                                .rowsUpdated()
                                .onErrorResume(error -> Mono.empty()))
                .subscribe(
                        ignored -> {
                        },
                        error -> log.debug("Could not save usage count {}: {}", name, error.getMessage())
                );
    }

    /** Removes counters with this prefix whose window started before the cutoff. */
    public void deleteOlderThan(String prefix, long cutoff) {
        databaseClient.sql("DELETE FROM usage_counters WHERE name LIKE :prefix AND window_start < :cutoff")
                .bind("prefix", prefix + "%")
                .bind("cutoff", cutoff)
                .fetch()
                .rowsUpdated()
                .subscribe(
                        ignored -> {
                        },
                        error -> log.debug("Could not delete old usage counts: {}", error.getMessage())
                );
    }

    public record SavedCount(String name, long windowStart, int requestCount) {
    }
}
