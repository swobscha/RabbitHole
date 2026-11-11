package spotifybot.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spotifybot.model.CreatedPlaylistHistoryItem;
import spotifybot.model.CreatedPlaylistResponse;
import spotifybot.model.PlaylistCreateRequest;
import spotifybot.model.ResolvedPlaylistTrack;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PlaylistHistoryService {

    private final DatabaseClient databaseClient;
    private final TrackKeyService trackKeyService;

    public PlaylistHistoryService(
            DatabaseClient databaseClient,
            TrackKeyService trackKeyService
    ) {
        this.databaseClient = databaseClient;
        this.trackKeyService = trackKeyService;
    }

    public Mono<Void> save(
            String spotifyUserId,
            PlaylistCreateRequest request,
            CreatedPlaylistResponse response,
            List<ResolvedPlaylistTrack> resolvedTracks
    ) {
        String playlistId = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();

        return databaseClient.sql("""
                        INSERT INTO created_playlists (
                            id,
                            spotify_user_id,
                            playlist_name,
                            seed_artist,
                            track_count,
                            spotify_playlist_url,
                            created_at
                        )
                        VALUES (
                            :id,
                            :spotifyUserId,
                            :playlistName,
                            :seedArtist,
                            :trackCount,
                            :spotifyPlaylistUrl,
                            :createdAt
                        )
                        """)
                .bind("id", playlistId)
                .bind("spotifyUserId", spotifyUserId)
                .bind("playlistName", safe(response.playlistName()))
                .bind("seedArtist", safe(request.seedArtist()))
                .bind("trackCount", response.trackCount())
                .bind("spotifyPlaylistUrl", safe(response.spotifyPlaylistUrl()))
                .bind("createdAt", createdAt)
                .fetch()
                .rowsUpdated()
                .then(saveUsedTracks(spotifyUserId, playlistId, resolvedTracks, createdAt));
    }

    public Flux<CreatedPlaylistHistoryItem> getHistory(String spotifyUserId) {
        return databaseClient.sql("""
                        SELECT
                            id AS "id",
                            playlist_name AS "playlistName",
                            seed_artist AS "seedArtist",
                            track_count AS "trackCount",
                            spotify_playlist_url AS "spotifyPlaylistUrl",
                            created_at AS "createdAt"
                        FROM created_playlists
                        WHERE spotify_user_id = :spotifyUserId
                        ORDER BY created_at DESC
                        """)
                .bind("spotifyUserId", spotifyUserId)
                .map((row, metadata) -> new CreatedPlaylistHistoryItem(
                        row.get("id", String.class),
                        row.get("playlistName", String.class),
                        row.get("seedArtist", String.class),
                        getInteger(row.get("trackCount")),
                        row.get("spotifyPlaylistUrl", String.class),
                        getString(row.get("createdAt"))
                ))
                .all();
    }

    public Flux<String> getUsedTrackKeys(String spotifyUserId) {
        return databaseClient.sql("""
                        SELECT normalized_key AS "normalizedKey"
                        FROM user_used_tracks
                        WHERE spotify_user_id = :spotifyUserId
                        """)
                .bind("spotifyUserId", spotifyUserId)
                .map((row, metadata) -> getString(row.get("normalizedKey")))
                .all()
                .filter(value -> value != null && !value.isBlank());
    }

    public Mono<Void> clearHistory(String spotifyUserId) {
        return databaseClient.sql("DELETE FROM user_used_tracks WHERE spotify_user_id = :spotifyUserId")
                .bind("spotifyUserId", spotifyUserId)
                .fetch()
                .rowsUpdated()
                .then(
                        databaseClient.sql("DELETE FROM created_playlists WHERE spotify_user_id = :spotifyUserId")
                                .bind("spotifyUserId", spotifyUserId)
                                .fetch()
                                .rowsUpdated()
                )
                .then();
    }

    /** Re-keys history saved under an old Spotify user id to the account's stable account_id. */
    public Mono<Void> moveToAccount(String legacyUserId, String accountId) {
        // A song both keys have used would break the unique key, so the old copy goes first.
        return databaseClient.sql("""
                        DELETE FROM user_used_tracks
                        WHERE spotify_user_id = :legacyUserId
                          AND normalized_key IN (
                              SELECT normalized_key FROM user_used_tracks WHERE spotify_user_id = :accountId
                          )
                        """)
                .bind("legacyUserId", legacyUserId)
                .bind("accountId", accountId)
                .fetch()
                .rowsUpdated()
                .then(databaseClient.sql("UPDATE user_used_tracks SET spotify_user_id = :accountId WHERE spotify_user_id = :legacyUserId")
                        .bind("accountId", accountId)
                        .bind("legacyUserId", legacyUserId)
                        .fetch()
                        .rowsUpdated())
                .then(databaseClient.sql("UPDATE created_playlists SET spotify_user_id = :accountId WHERE spotify_user_id = :legacyUserId")
                        .bind("accountId", accountId)
                        .bind("legacyUserId", legacyUserId)
                        .fetch()
                        .rowsUpdated())
                .then();
    }

    private Mono<Void> saveUsedTracks(
            String spotifyUserId,
            String playlistId,
            List<ResolvedPlaylistTrack> resolvedTracks,
            String createdAt
    ) {
        if (resolvedTracks == null || resolvedTracks.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(resolvedTracks)
                .concatMap(resolvedTrack -> {
                    if (resolvedTrack == null
                            || resolvedTrack.weightedTrack() == null
                            || resolvedTrack.weightedTrack().track() == null) {
                        return Mono.<Void>empty();
                    }

                    String trackName = resolvedTrack.weightedTrack().track().name();
                    String artistName = resolvedTrack.weightedTrack().track().artistName();
                    String normalizedKey = trackKeyService.normalizedKey(artistName, trackName);

                    if (normalizedKey.isBlank()) {
                        return Mono.<Void>empty();
                    }

                    return databaseClient.sql("""
                                    INSERT INTO user_used_tracks (
                                        id,
                                        spotify_user_id,
                                        playlist_id,
                                        track_name,
                                        artist_name,
                                        normalized_key,
                                        spotify_uri,
                                        created_at
                                    )
                                    VALUES (
                                        :id,
                                        :spotifyUserId,
                                        :playlistId,
                                        :trackName,
                                        :artistName,
                                        :normalizedKey,
                                        :spotifyUri,
                                        :createdAt
                                    )
                                    """)
                            .bind("id", UUID.randomUUID().toString())
                            .bind("spotifyUserId", spotifyUserId)
                            .bind("playlistId", safe(playlistId))
                            .bind("trackName", safe(trackName))
                            .bind("artistName", safe(artistName))
                            .bind("normalizedKey", normalizedKey)
                            .bind("spotifyUri", safe(resolvedTrack.spotifyUri()))
                            .bind("createdAt", safe(createdAt))
                            .fetch()
                            .rowsUpdated()
                            .then()
                            // A track this user has already used is the only expected failure.
                            .onErrorResume(DataIntegrityViolationException.class, duplicate -> Mono.empty());
                })
                .then();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String getString(Object value) {
        if (value == null) {
            return "";
        }

        return String.valueOf(value);
    }

    private int getInteger(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }

        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        return 0;
    }
}
