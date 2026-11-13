package rabbithole.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import rabbithole.model.SpotifyArtistSearchResult;
import rabbithole.model.SpotifySession;
import rabbithole.service.SpotifyArtistSearchService;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/spotify/artists")
public class SpotifyArtistController {

    private final SpotifyArtistSearchService spotifyArtistSearchService;

    public SpotifyArtistController(SpotifyArtistSearchService spotifyArtistSearchService) {
        this.spotifyArtistSearchService = spotifyArtistSearchService;
    }

    @GetMapping("/search")
    public Mono<List<SpotifyArtistSearchResult>> searchArtists(
            Optional<SpotifySession> session,
            @RequestParam String query
    ) {
        return spotifyArtistSearchService.searchArtists(session, query);
    }
}