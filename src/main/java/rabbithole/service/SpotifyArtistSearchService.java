package rabbithole.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import rabbithole.model.SpotifyArtistSearchResult;
import rabbithole.model.SpotifySession;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class SpotifyArtistSearchService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    // Matches the seed artist limit on generate and prefetch.
    private static final int MAX_QUERY_LENGTH = 100;

    // Each search is a Spotify request, and visitors often search the same names, or search again.
    private final Cache<String, List<SpotifyArtistSearchResult>> recentSearches = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterWrite(Duration.ofHours(24))
            .build();

    private final SpotifyAuthService spotifyAuthService;
    private final SpotifyArtistSearchStore searchStore;
    private final WebClient webClient;

    public SpotifyArtistSearchService(
            SpotifyAuthService spotifyAuthService,
            SpotifyArtistSearchStore searchStore,
            SpotifyRateLimitGuard rateLimitGuard,
            @Value("${SPOTIFY_API_URL:https://api.spotify.com/v1}") String apiUrl
    ) {
        this.spotifyAuthService = spotifyAuthService;
        this.searchStore = searchStore;
        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .filter(rateLimitGuard.filter())
                .build();
    }

    public Mono<List<SpotifyArtistSearchResult>> searchArtists(Optional<SpotifySession> session, String query) {
        if (query == null || query.isBlank()) {
            return Mono.just(List.of());
        }

        if (query.trim().length() > MAX_QUERY_LENGTH) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Artist names must be " + MAX_QUERY_LENGTH + " characters or fewer."
            ));
        }

        String trimmed = query.trim();
        String key = normalize(trimmed);
        List<SpotifyArtistSearchResult> remembered = recentSearches.getIfPresent(key);

        if (remembered != null) {
            return Mono.just(remembered);
        }

        // Memory first, then results saved before a restart, and only then Spotify.
        return searchStore.find(key)
                .doOnNext(saved -> recentSearches.put(key, saved))
                .switchIfEmpty(Mono.defer(() -> searchSpotify(session, trimmed)
                        .doOnNext(results -> {
                            recentSearches.put(key, results);
                            searchStore.save(key, results);
                        })));
    }

    private Mono<List<SpotifyArtistSearchResult>> searchSpotify(Optional<SpotifySession> session, String query) {
        return spotifyAuthService.getAccessToken(session)
                .flatMap(accessToken ->
                        webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/search")
                                        .queryParam("q", "{query}")
                                        .queryParam("type", "artist")
                                        .queryParam("limit", 5)
                                        .build(query))
                                .headers(headers -> headers.setBearerAuth(accessToken))
                                .retrieve()
                                .bodyToMono(JsonNode.class)
                                .timeout(REQUEST_TIMEOUT)
                                .map(this::parseArtistResults)
                );
    }

    /** "Daft  Punk" and "daft punk" are the same search. */
    private static String normalize(String query) {
        return query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Clears the in-memory copy, as a restart would. Used by tests. */
    public void forgetRecentSearches() {
        recentSearches.invalidateAll();
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