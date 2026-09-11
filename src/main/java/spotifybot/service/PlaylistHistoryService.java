package spotifybot.service;

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
            PlaylistCreateRequest request,
            CreatedPlaylistResponse response,
            List<ResolvedPlaylistTrack> resolvedTracks
    ) {
        String playlistId = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();

        return databaseClient.sql("""
                        INSERT INTO created_playlists (
                            id,
                            playlist_name,
                            seed_artist,
                            track_count,
                            spotify_playlist_url,
                            created_at
                        )
                        VALUES (
                            :id,
                            :playlistName,
                            :seedArtist,
                            :trackCount,
                            :spotifyPlaylistUrl,
                            :createdAt
                        )
                        """)
                .bind("id", playlistId)
                .bind("playlistName", safe(response.playlistName()))
                .bind("seedArtist", safe(request.seedArtist()))
                .bind("trackCount", response.trackCount())
                .bind("spotifyPlaylistUrl", safe(response.spotifyPlaylistUrl()))
                .bind("createdAt", createdAt)
                .fetch()
                .rowsUpdated()
                .then(saveUsedTracks(playlistId, resolvedTracks, createdAt));
    }

    public Flux<CreatedPlaylistHistoryItem> getHistory() {
        return databaseClient.sql("""
                        SELECT
                            id AS "id",
                            playlist_name AS "playlistName",
                            seed_artist AS "seedArtist",
                            track_count AS "trackCount",
                            spotify_playlist_url AS "spotifyPlaylistUrl",
                            created_at AS "createdAt"
                        FROM created_playlists
                        ORDER BY created_at DESC
                        """)
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

    public Flux<String> getUsedTrackKeys() {
        return databaseClient.sql("""
                        SELECT normalized_key AS "normalizedKey"
                        FROM used_tracks
                        """)
                .map((row, metadata) -> getString(row.get("normalizedKey")))
                .all()
                .filter(value -> value != null && !value.isBlank());
    }

    public Mono<Void> clearHistory() {
        return databaseClient.sql("DELETE FROM used_tracks")
                .fetch()
                .rowsUpdated()
                .then(
                        databaseClient.sql("DELETE FROM created_playlists")
                                .fetch()
                                .rowsUpdated()
                )
                .then();
    }

    private Mono<Void> saveUsedTracks(
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

                    if (normalizedKey == null || normalizedKey.isBlank()) {
                        return Mono.<Void>empty();
                    }

                    return databaseClient.sql("""
                                    INSERT INTO used_tracks (
                                        id,
                                        playlist_id,
                                        track_name,
                                        artist_name,
                                        normalized_key,
                                        spotify_uri,
                                        created_at
                                    )
                                    VALUES (
                                        :id,
                                        :playlistId,
                                        :trackName,
                                        :artistName,
                                        :normalizedKey,
                                        :spotifyUri,
                                        :createdAt
                                    )
                                    ON CONFLICT (normalized_key) DO NOTHING
                                    """)
                            .bind("id", UUID.randomUUID().toString())
                            .bind("playlistId", safe(playlistId))
                            .bind("trackName", safe(trackName))
                            .bind("artistName", safe(artistName))
                            .bind("normalizedKey", safe(normalizedKey))
                            .bind("spotifyUri", safe(resolvedTrack.spotifyUri()))
                            .bind("createdAt", safe(createdAt))
                            .fetch()
                            .rowsUpdated()
                            .then()
                            .onErrorResume(error -> {
                                System.out.println(
                                        "Could not save used track"
                                                + " | artist=" + safe(artistName)
                                                + " | track=" + safe(trackName)
                                                + " | error=" + error.getMessage()
                                );

                                return Mono.empty();
                            });
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