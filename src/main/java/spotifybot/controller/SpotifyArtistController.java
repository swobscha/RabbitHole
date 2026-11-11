package spotifybot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import spotifybot.model.SpotifyArtistSearchResult;
import spotifybot.model.SpotifySession;
import spotifybot.service.SpotifyArtistSearchService;

import java.util.List;

@RestController
@RequestMapping("/api/spotify/artists")
public class SpotifyArtistController {

    private final SpotifyArtistSearchService spotifyArtistSearchService;

    public SpotifyArtistController(SpotifyArtistSearchService spotifyArtistSearchService) {
        this.spotifyArtistSearchService = spotifyArtistSearchService;
    }

    @GetMapping("/search")
    public Mono<List<SpotifyArtistSearchResult>> searchArtists(
            SpotifySession session,
            @RequestParam String query
    ) {
        return spotifyArtistSearchService.searchArtists(session, query);
    }
}