package rabbithole.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import rabbithole.model.SpotifyArtistSearchResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Saves artist search results so the same name isn't searched on Spotify again for a while, even
 * across restarts. Results are reused for a week, or a day when Spotify found nothing, since a
 * missing artist may be a typo or be added soon.
 */
@Service
public class SpotifyArtistSearchStore {

    private static final Logger log = LoggerFactory.getLogger(SpotifyArtistSearchStore.class);

    static final Duration RESULTS_LIFETIME = Duration.ofDays(7);
    static final Duration EMPTY_RESULTS_LIFETIME = Duration.ofDays(1);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<SpotifyArtistSearchResult>> RESULT_LIST = new TypeReference<>() {
    };

    private final DatabaseClient databaseClient;

    public SpotifyArtistSearchStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /** Saved results for this search, or empty when there are none or they've expired. */
    public Mono<List<SpotifyArtistSearchResult>> find(String normalizedQuery) {
        return databaseClient.sql("""
                        SELECT results, checked_at AS "checkedAt"
                        FROM spotify_artist_searches
                        WHERE id = :id
                        """)
                .bind("id", hash(normalizedQuery))
                .map(row -> new SavedSearch(row.get("results", String.class), row.get("checkedAt", Long.class)))
                .one()
                .flatMap(saved -> {
                    List<SpotifyArtistSearchResult> results = JSON.readValue(saved.results(), RESULT_LIST);
                    Duration lifetime = results.isEmpty() ? EMPTY_RESULTS_LIFETIME : RESULTS_LIFETIME;

                    return saved.checkedAt() > System.currentTimeMillis() - lifetime.toMillis()
                            ? Mono.just(results)
                            : Mono.empty();
                })
                .onErrorResume(error -> {
                    log.warn("Could not read saved artist search results; searching Spotify instead.", error);
                    return Mono.empty();
                });
    }

    /** Saves in the background; a failed save only means the name is searched again later. */
    public void save(String normalizedQuery, List<SpotifyArtistSearchResult> results) {
        String id = hash(normalizedQuery);
        long now = System.currentTimeMillis();
        String json = JSON.writeValueAsString(results);

        databaseClient.sql("UPDATE spotify_artist_searches SET results = :results, checked_at = :now WHERE id = :id")
                .bind("id", id)
                .bind("results", json)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows > 0
                        ? Mono.<Long>empty()
                        : databaseClient.sql("""
                                        INSERT INTO spotify_artist_searches (id, results, checked_at)
                                        VALUES (:id, :results, :now)
                                        """)
                                .bind("id", id)
                                .bind("results", json)
                                .bind("now", now)
                                .fetch()
                                .rowsUpdated())
                .subscribe(
                        ignored -> {
                        },
                        error -> log.debug("Could not save artist search results: {}", error.getMessage())
                );
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available.", error);
        }
    }

    private record SavedSearch(String results, long checkedAt) {
    }
}
