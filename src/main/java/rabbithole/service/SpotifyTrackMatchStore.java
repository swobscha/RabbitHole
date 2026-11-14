package rabbithole.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Remembers which Spotify track each looked-up song matched, so the same song is only ever searched
 * once, even across restarts. Matches are kept for good; misses are rechecked after a week, in case
 * the song has since been added to Spotify.
 */
@Service
public class SpotifyTrackMatchStore {

    private static final Logger log = LoggerFactory.getLogger(SpotifyTrackMatchStore.class);

    private static final Duration MISS_LIFETIME = Duration.ofDays(7);

    private final DatabaseClient databaseClient;

    public SpotifyTrackMatchStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /** The stored answer for a lookup, or empty when it was never looked up or the miss has expired. */
    public Mono<StoredMatch> find(String lookupKey) {
        return databaseClient.sql("""
                        SELECT spotify_uri AS "spotifyUri", checked_at AS "checkedAt"
                        FROM spotify_track_matches
                        WHERE id = :id
                        """)
                .bind("id", hash(lookupKey))
                .map(row -> new StoredMatch(row.get("spotifyUri", String.class), row.get("checkedAt", Long.class)))
                .one()
                .filter(stored -> stored.spotifyUri() != null
                        || stored.checkedAt() > System.currentTimeMillis() - MISS_LIFETIME.toMillis())
                .onErrorResume(error -> {
                    log.warn("Could not read a saved Spotify match; searching Spotify instead.", error);
                    return Mono.empty();
                });
    }

    /** Saves in the background; a failed save only means the song is searched again later. */
    public void save(String lookupKey, String spotifyUri) {
        String id = hash(lookupKey);
        long now = System.currentTimeMillis();

        DatabaseClient.GenericExecuteSpec update = databaseClient
                .sql("UPDATE spotify_track_matches SET spotify_uri = :uri, checked_at = :now WHERE id = :id")
                .bind("id", id)
                .bind("now", now);

        DatabaseClient.GenericExecuteSpec insert = databaseClient
                .sql("INSERT INTO spotify_track_matches (id, spotify_uri, checked_at) VALUES (:id, :uri, :now)")
                .bind("id", id)
                .bind("now", now);

        withUri(update, spotifyUri)
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows > 0 ? Mono.<Long>empty() : withUri(insert, spotifyUri).fetch().rowsUpdated())
                .subscribe(
                        ignored -> {
                        },
                        error -> log.debug("Could not save a Spotify match: {}", error.getMessage())
                );
    }

    private static DatabaseClient.GenericExecuteSpec withUri(DatabaseClient.GenericExecuteSpec spec, String spotifyUri) {
        return spotifyUri == null ? spec.bindNull("uri", String.class) : spec.bind("uri", spotifyUri);
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

    /** spotifyUri is null when Spotify had no match. */
    public record StoredMatch(String spotifyUri, long checkedAt) {
    }
}
