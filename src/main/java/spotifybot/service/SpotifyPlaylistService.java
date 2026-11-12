package spotifybot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import spotifybot.model.CreatedPlaylistResponse;
import spotifybot.model.PlaylistCreateRequest;
import spotifybot.model.ResolvedPlaylistTrack;
import spotifybot.model.SpotifyCreatedPlaylist;
import spotifybot.model.SpotifySession;
import spotifybot.model.WeightedTrack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SpotifyPlaylistService {

    private static final Logger log = LoggerFactory.getLogger(SpotifyPlaylistService.class);

    private static final int MAX_PLAYLIST_NAME_LENGTH = 100;
    private static final int MAX_TRACKS_PER_SPOTIFY_REQUEST = 100;
    private static final Pattern SPOTIFY_TRACK_URI = Pattern.compile("spotify:track:[A-Za-z0-9]+");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final SpotifyAuthService spotifyAuthService;
    private final PlaylistHistoryService playlistHistoryService;
    private final WebClient webClient;

    public SpotifyPlaylistService(
            SpotifyAuthService spotifyAuthService,
            PlaylistHistoryService playlistHistoryService,
            @Value("${SPOTIFY_API_URL:https://api.spotify.com/v1}") String apiUrl
    ) {
        this.spotifyAuthService = spotifyAuthService;
        this.playlistHistoryService = playlistHistoryService;
        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .build();
    }

    public Mono<CreatedPlaylistResponse> createPlaylist(SpotifySession session, PlaylistCreateRequest request) {
        validateCreateRequest(request);

        List<ResolvedPlaylistTrack> resolvedTracks = collectAlreadyResolvedTracks(request);

        if (resolvedTracks.isEmpty()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No Spotify-compatible tracks were found. Generate a new playlist first."
            ));
        }

        List<String> trackUris = resolvedTracks.stream()
                .map(ResolvedPlaylistTrack::spotifyUri)
                .toList();

        return spotifyAuthService.getValidAccessToken(session)
                .flatMap(accessToken ->
                        createEmptyPlaylist(accessToken, request.playlistName().trim())
                                .flatMap(spotifyPlaylist ->
                                        addTracksOrRemovePlaylist(accessToken, spotifyPlaylist.id(), trackUris)
                                                .thenReturn(new CreatedPlaylistResponse(
                                                        spotifyPlaylist.name(),
                                                        resolvedTracks.size(),
                                                        "Playlist created on Spotify",
                                                        playlistUrl(spotifyPlaylist)
                                                ))
                                )
                )
                .flatMap(response -> saveToHistory(session, request, response, resolvedTracks));
    }

    private void validateCreateRequest(PlaylistCreateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Playlist request is missing.");
        }

        if (request.playlistName() == null || request.playlistName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Playlist name is required.");
        }

        if (request.playlistName().trim().length() > MAX_PLAYLIST_NAME_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Playlist name must be " + MAX_PLAYLIST_NAME_LENGTH + " characters or fewer."
            );
        }

        if (request.safeTracks().size() > MAX_TRACKS_PER_SPOTIFY_REQUEST) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A playlist can have at most " + MAX_TRACKS_PER_SPOTIFY_REQUEST + " tracks."
            );
        }
    }

    private List<ResolvedPlaylistTrack> collectAlreadyResolvedTracks(PlaylistCreateRequest request) {
        List<ResolvedPlaylistTrack> resolvedTracks = new ArrayList<>();
        Set<String> seenUris = new HashSet<>();

        int targetCount = request.safeTargetTrackCount();

        for (WeightedTrack weightedTrack : request.safeTracks()) {
            if (resolvedTracks.size() >= targetCount) {
                break;
            }

            if (weightedTrack == null || weightedTrack.track() == null) {
                continue;
            }

            String spotifyUri = weightedTrack.track().spotifyUri();

            if (spotifyUri == null || !SPOTIFY_TRACK_URI.matcher(spotifyUri).matches()) {
                continue;
            }

            if (seenUris.contains(spotifyUri)) {
                continue;
            }

            resolvedTracks.add(new ResolvedPlaylistTrack(weightedTrack, spotifyUri));
            seenUris.add(spotifyUri);
        }

        return resolvedTracks;
    }

    private Mono<SpotifyCreatedPlaylist> createEmptyPlaylist(String accessToken, String playlistName) {
        return webClient.post()
                .uri("/me/playlists")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", playlistName,
                        "description", "Generated by MusicFinder",
                        "public", false
                ))
                .retrieve()
                .bodyToMono(SpotifyCreatedPlaylist.class)
                .timeout(REQUEST_TIMEOUT);
    }

    private Mono<Void> addTracksOrRemovePlaylist(String accessToken, String playlistId, List<String> trackUris) {
        return webClient.post()
                .uri("/playlists/{playlistId}/items", playlistId)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("uris", trackUris))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(REQUEST_TIMEOUT)
                .then()
                .onErrorResume(error ->
                        removeEmptyPlaylist(accessToken, playlistId).then(Mono.<Void>error(error))
                );
    }

    private Mono<Void> removeEmptyPlaylist(String accessToken, String playlistId) {
        return webClient.delete()
                .uri(uriBuilder -> uriBuilder
                        .path("/me/library")
                        .queryParam("uris", "{uri}")
                        .build("spotify:playlist:" + playlistId))
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .toBodilessEntity()
                .timeout(REQUEST_TIMEOUT)
                .then()
                .onErrorResume(error -> {
                    log.error("Could not remove empty Spotify playlist {} after adding tracks failed.", playlistId, error);
                    return Mono.empty();
                });
    }

    /** The playlist already exists on Spotify, so a history failure must not be reported as a failed create. */
    private Mono<CreatedPlaylistResponse> saveToHistory(
            SpotifySession session,
            PlaylistCreateRequest request,
            CreatedPlaylistResponse response,
            List<ResolvedPlaylistTrack> resolvedTracks
    ) {
        return playlistHistoryService.save(session.spotifyUserId(), request, response, resolvedTracks)
                .thenReturn(response)
                .onErrorResume(error -> {
                    log.error("Could not save a created playlist to history.", error);

                    return Mono.just(new CreatedPlaylistResponse(
                            response.playlistName(),
                            response.trackCount(),
                            "Playlist created on Spotify, but it could not be saved to your history",
                            response.spotifyPlaylistUrl()
                    ));
                });
    }

    private String playlistUrl(SpotifyCreatedPlaylist playlist) {
        Map<String, String> externalUrls = playlist.externalUrls();
        String url = externalUrls == null ? null : externalUrls.get("spotify");

        return url == null || url.isBlank()
                ? "https://open.spotify.com/playlist/" + playlist.id()
                : url;
    }
}
