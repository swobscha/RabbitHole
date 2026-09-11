package spotifybot.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import spotifybot.model.CreatedPlaylistHistoryItem;
import spotifybot.model.CreatedPlaylistResponse;
import spotifybot.model.PlaylistCreateRequest;
import spotifybot.model.PlaylistGenerationRequest;
import spotifybot.model.PlaylistGenerationResult;
import spotifybot.service.PlaylistGenerationService;
import spotifybot.service.PlaylistHistoryService;
import spotifybot.service.SpotifyPlaylistService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/playlists")
@CrossOrigin(origins = "http://localhost:5173")
public class PlaylistController {

    private final PlaylistGenerationService playlistGenerationService;
    private final SpotifyPlaylistService spotifyPlaylistService;
    private final PlaylistHistoryService playlistHistoryService;

    public PlaylistController(
            PlaylistGenerationService playlistGenerationService,
            SpotifyPlaylistService spotifyPlaylistService,
            PlaylistHistoryService playlistHistoryService
    ) {
        this.playlistGenerationService = playlistGenerationService;
        this.spotifyPlaylistService = spotifyPlaylistService;
        this.playlistHistoryService = playlistHistoryService;
    }

    @PostMapping("/generate")
    public Mono<PlaylistGenerationResult> generatePlaylist(
            @RequestBody PlaylistGenerationRequest request
    ) {
        return playlistGenerationService.generatePlaylist(request);
    }

    @PostMapping("/create")
    public Mono<CreatedPlaylistResponse> createPlaylist(
            @RequestBody PlaylistCreateRequest request
    ) {
        return spotifyPlaylistService.createPlaylist(request);
    }

    @GetMapping("/history")
    public Mono<List<CreatedPlaylistHistoryItem>> getHistory() {
        return playlistHistoryService.getHistory().collectList();
    }

    @DeleteMapping("/history")
    public Mono<Void> clearHistory() {
        return playlistHistoryService.clearHistory();
    }

    @GetMapping("/demo")
    public Mono<PlaylistGenerationResult> demoPlaylist() {
        PlaylistGenerationRequest request = new PlaylistGenerationRequest(
                "Daft Punk",
                null,
                "Demo Bot Playlist",
                5,
                List.of(),
                List.of("Justice", "Kavinsky"),
                List.of("electronic", "dance", "house", "synthwave"),
                0.7,
                UUID.randomUUID().toString(),
                true,
                0.6,
                0.6,
                0.5,
                100
        );

        return playlistGenerationService.generatePlaylist(request);
    }
}