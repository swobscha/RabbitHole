package spotifybot.service;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import spotifybot.model.SpotifyArtistResponse;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SpotifyArtistService {

    private final WebClient webClient;
    private final Map<String, Set<String>> genreCache = new ConcurrentHashMap<>();

    public SpotifyArtistService() {
        this.webClient = WebClient.builder()
                .baseUrl("https://api.spotify.com/v1")
                .build();
    }

    public Mono<Set<String>> getArtistGenres(String accessToken, String artistId) {
        if (artistId == null || artistId.isBlank() || artistId.equals("unknown")) {
            return Mono.just(Set.of());
        }

        Set<String> cachedGenres = genreCache.get(artistId);

        if (cachedGenres != null) {
            return Mono.just(cachedGenres);
        }

        return webClient.get()
                .uri("/artists/{id}", artistId)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(SpotifyArtistResponse.class)
                .map(response -> {
                    if (response.genres() == null) {
                        return Set.<String>of();
                    }

                    return response.genres()
                            .stream()
                            .filter(Objects::nonNull)
                            .map(String::toLowerCase)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                })
                .doOnNext(genres -> genreCache.put(artistId, genres))
                .onErrorResume(error -> Mono.just(Set.of()));
    }
}