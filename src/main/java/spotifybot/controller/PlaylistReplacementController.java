package spotifybot.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import spotifybot.model.PlaylistReplaceTrackRequest;
import spotifybot.model.PlaylistReplaceTrackResult;
import spotifybot.model.SpotifySession;
import spotifybot.service.PlaylistGenerationService;

@RestController
@RequestMapping("/api/playlists")
public class PlaylistReplacementController {

    private final PlaylistGenerationService playlistGenerationService;

    public PlaylistReplacementController(PlaylistGenerationService playlistGenerationService) {
        this.playlistGenerationService = playlistGenerationService;
    }

    @PostMapping("/replace-track")
    public Mono<PlaylistReplaceTrackResult> replaceTrack(
            SpotifySession session,
            @RequestBody PlaylistReplaceTrackRequest request
    ) {
        return playlistGenerationService.replaceTrack(session, request)
                .map(PlaylistReplaceTrackResult::new);
    }
}