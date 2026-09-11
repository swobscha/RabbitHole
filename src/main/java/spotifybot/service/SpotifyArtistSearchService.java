package spotifybot.service;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import spotifybot.model.SpotifyArtistSearchResult;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

@Service
public class SpotifyArtistSearchService {

    private final SpotifyAuthService spotifyAuthService;
    private final WebClient webClient;

    public SpotifyArtistSearchService(SpotifyAuthService spotifyAuthService) {
        this.spotifyAuthService = spotifyAuthService;
        this.webClient = WebClient.builder()
                .baseUrl("https://api.spotify.com/v1")
                .build();
    }

    public Mono<List<SpotifyArtistSearchResult>> searchArtists(String query) {
        if (query == null || query.isBlank()) {
            return Mono.just(List.of());
        }

        return spotifyAuthService.getValidAccessToken()
                .flatMap(accessToken ->
                        webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/search")
                                        .queryParam("q", query.trim())
                                        .queryParam("type", "artist")
                                        .queryParam("limit", 5)
                                        .build())
                                .headers(headers -> headers.setBearerAuth(accessToken))
                                .retrieve()
                                .bodyToMono(JsonNode.class)
                                .map(this::parseArtistResults)
                )
                .onErrorResume(error -> {
                    System.out.println("Spotify artist search failed: " + error.getMessage());
                    return Mono.just(List.of());
                });
    }

    private List<SpotifyArtistSearchResult> parseArtistResults(JsonNode json) {
        JsonNode items = json.path("artists").path("items");

        if (!items.isArray() || items.isEmpty()) {
            return List.of();
        }

        List<SpotifyArtistSearchResult> results = new ArrayList<>();

        items.forEach(item -> {
            String id = item.path("id").asText("");
            String name = item.path("name").asText("");
            String imageUrl = imageUrl(item.path("images"));
            long followers = safeLong(item.path("followers").path("total").asText("0"));
            List<String> genres = genres(item.path("genres"));
            String spotifyUrl = item.path("external_urls").path("spotify").asText("");

            if (!id.isBlank() && !name.isBlank()) {
                results.add(new SpotifyArtistSearchResult(
                        id,
                        name,
                        imageUrl,
                        followers,
                        genres,
                        spotifyUrl
                ));
            }
        });

        return results;
    }

    private String imageUrl(JsonNode images) {
        if (!images.isArray() || images.isEmpty()) {
            return "";
        }

        return images.get(0).path("url").asText("");
    }

    private List<String> genres(JsonNode genresNode) {
        if (!genresNode.isArray() || genresNode.isEmpty()) {
            return List.of();
        }

        List<String> genres = new ArrayList<>();

        genresNode.forEach(genre -> {
            String value = genre.asText("");

            if (!value.isBlank()) {
                genres.add(value);
            }
        });

        return genres;
    }

    private long safeLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return 0;
        }
    }
}