package rabbithole.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import rabbithole.model.TrackCandidate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SpotifyTrackSearchService {

    private static final Logger log = LoggerFactory.getLogger(SpotifyTrackSearchService.class);

    private static final int MIN_TRACK_MATCH_SCORE = 50;
    private static final int MIN_ARTIST_MATCH_SCORE = 55;
    private static final int MIN_TOTAL_MATCH_SCORE = 115;

    private static final int MAX_CACHE_ENTRIES = 20_000;
    private static final Duration CACHE_LIFETIME = Duration.ofHours(24);

    private final WebClient webClient;
    private final SpotifyRateLimitGuard rateLimitGuard;
    private final SpotifyTrackMatchStore matchStore;

    // In front of the database copy, so repeat lookups during one generation stay in memory.
    private final Cache<String, String> uriCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES)
            .expireAfterWrite(CACHE_LIFETIME)
            .build();
    private final Cache<String, Boolean> missCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES)
            .expireAfterWrite(CACHE_LIFETIME)
            .build();

    public SpotifyTrackSearchService(
            SpotifyRateLimitGuard rateLimitGuard,
            SpotifyTrackMatchStore matchStore,
            @Value("${SPOTIFY_API_URL:https://api.spotify.com/v1}") String apiUrl
    ) {
        this.rateLimitGuard = rateLimitGuard;
        this.matchStore = matchStore;
        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .filter(rateLimitGuard.filter())
                .build();
    }

    /**
     * Finds the Spotify track for a candidate: from memory, then from the database, and only then by
     * searching Spotify. {@link TrackMatch#searchedSpotify()} says whether a Spotify search was spent.
     */
    public Mono<TrackMatch> findSpotifyUri(
            String accessToken,
            TrackCandidate track,
            String requiredSpotifyArtistId
    ) {
        if (track.spotifyUri() != null && !track.spotifyUri().isBlank()) {
            return Mono.just(TrackMatch.remembered(track.spotifyUri()));
        }

        String cleanRequiredArtistId = requiredSpotifyArtistId == null
                ? ""
                : requiredSpotifyArtistId.trim();

        String cacheKey = cacheKey(
                cleanRequiredArtistId,
                track.artistName(),
                track.name()
        );

        String cachedUri = uriCache.getIfPresent(cacheKey);

        if (cachedUri != null) {
            return Mono.just(TrackMatch.remembered(cachedUri));
        }

        if (missCache.getIfPresent(cacheKey) != null) {
            return Mono.just(TrackMatch.remembered(null));
        }

        return matchStore.find(cacheKey)
                .map(stored -> {
                    rememberInMemory(cacheKey, stored.spotifyUri());
                    return TrackMatch.remembered(stored.spotifyUri());
                })
                .switchIfEmpty(Mono.defer(() -> searchAndRemember(accessToken, track, cleanRequiredArtistId, cacheKey)));
    }

    /** Clears the in-memory copies, as a restart would. The database copy stays. */
    public void forgetCachedMatches() {
        uriCache.invalidateAll();
        missCache.invalidateAll();
    }

    private Mono<TrackMatch> searchAndRemember(
            String accessToken,
            TrackCandidate track,
            String requiredSpotifyArtistId,
            String cacheKey
    ) {
        Mono<TrackMatch> search = searchSpotifyOnce(accessToken, track.artistName(), track.name(), requiredSpotifyArtistId)
                .map(result -> {
                    if (result.hasUri()) {
                        rememberInMemory(cacheKey, result.uri());
                        matchStore.save(cacheKey, result.uri());
                        return TrackMatch.searched(result.uri());
                    }

                    // A failed request isn't remembered, so the song can be tried again later.
                    if (result.definitiveMiss()) {
                        rememberInMemory(cacheKey, null);
                        matchStore.save(cacheKey, null);
                    }

                    log.debug("Spotify could not resolve | artist={} | track={}", track.artistName(), track.name());
                    return TrackMatch.searched(null);
                })
                .cache();

        // Searches still running when a playlist fills up are no longer waited for, but Spotify has
        // already counted them, so they're finished and saved anyway instead of being thrown away.
        search.subscribe(
                ignored -> {
                },
                ignored -> {
                }
        );

        return search;
    }

    private void rememberInMemory(String cacheKey, String spotifyUri) {
        if (spotifyUri == null) {
            missCache.put(cacheKey, Boolean.TRUE);
        } else {
            uriCache.put(cacheKey, spotifyUri);
        }
    }

    private Mono<LookupResult> searchSpotifyOnce(
            String accessToken,
            String artistName,
            String trackName,
            String requiredSpotifyArtistId
    ) {
        String cleanArtist = TrackText.simplify(artistName);
        String cleanTrack = TrackText.searchTitle(artistName, trackName);

        if (cleanTrack.isBlank()) {
            return Mono.just(LookupResult.miss());
        }

        String query = cleanTrack + " " + cleanArtist;

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search")
                        .queryParam("q", "{query}")
                        .queryParam("type", "track")
                        .queryParam("limit", 10)
                        .build(query))
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(8))
                .flatMap(json -> {
                    JsonNode items = json.path("tracks").path("items");

                    if (!items.isArray() || items.isEmpty()) {
                        return Mono.just(LookupResult.miss());
                    }

                    List<ScoredSpotifyTrack> scoredTracks = new ArrayList<>();

                    items.forEach(item -> {
                        String uri = item.path("uri").asText("");
                        String spotifyTrackName = item.path("name").asText("");

                        List<String> spotifyArtists = new ArrayList<>();
                        List<String> spotifyArtistIds = new ArrayList<>();

                        JsonNode artistsNode = item.path("artists");

                        if (artistsNode.isArray()) {
                            artistsNode.forEach(artist -> {
                                String spotifyArtistName = artist.path("name").asText("");
                                String spotifyArtistId = artist.path("id").asText("");

                                if (!spotifyArtistName.isBlank()) {
                                    spotifyArtists.add(spotifyArtistName);
                                }

                                if (!spotifyArtistId.isBlank()) {
                                    spotifyArtistIds.add(spotifyArtistId);
                                }
                            });
                        }

                        if (!requiredSpotifyArtistId.isBlank()
                                && !spotifyArtistIds.contains(requiredSpotifyArtistId)) {
                            log.debug(
                                    "Spotify result rejected, wrong seed artist | wanted={} - {} | got={} by {} | artistIds={} | requiredArtistId={}",
                                    artistName,
                                    trackName,
                                    spotifyTrackName,
                                    spotifyArtists,
                                    spotifyArtistIds,
                                    requiredSpotifyArtistId
                            );

                            return;
                        }

                        SpotifyMatchScore matchScore = scoreSpotifyResult(
                                spotifyTrackName,
                                spotifyArtists,
                                cleanArtist,
                                cleanTrack
                        );

                        if (uri.isBlank()) {
                            return;
                        }

                        if (!matchScore.valid()) {
                            log.debug(
                                    "Spotify result rejected, weak match | wanted={} - {} | got={} by {} | trackScore={} | artistScore={} | total={}",
                                    artistName,
                                    trackName,
                                    spotifyTrackName,
                                    spotifyArtists,
                                    matchScore.trackScore(),
                                    matchScore.artistScore(),
                                    matchScore.totalScore()
                            );

                            return;
                        }

                        scoredTracks.add(new ScoredSpotifyTrack(
                                uri,
                                matchScore.totalScore(),
                                matchScore.trackScore(),
                                matchScore.artistScore(),
                                spotifyTrackName,
                                spotifyArtists,
                                spotifyArtistIds
                        ));
                    });

                    return scoredTracks.stream()
                            .max(Comparator.comparingInt(ScoredSpotifyTrack::totalScore))
                            .map(result -> {
                                log.debug(
                                        "Spotify resolved | wanted={} - {} | got={} by {} | trackScore={} | artistScore={} | total={}",
                                        artistName,
                                        trackName,
                                        result.spotifyTrackName(),
                                        result.spotifyArtists(),
                                        result.trackScore(),
                                        result.artistScore(),
                                        result.totalScore()
                                );

                                return Mono.just(LookupResult.found(result.uri()));
                            })
                            .orElse(Mono.just(LookupResult.miss()));
                })
                .onErrorResume(error -> handleSpotifyError(error, query));
    }

    private Mono<LookupResult> handleSpotifyError(Throwable error, String query) {
        // Includes the shared pause, which must stop the generation rather than count as a miss.
        if (error instanceof ResponseStatusException statusException) {
            return Mono.error(statusException);
        }

        if (error instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();

            log.warn(
                    "Spotify search failed | status={} | query={} | body={}",
                    status,
                    query,
                    responseException.getResponseBodyAsString()
            );

            // The shared guard has already started the pause Spotify asked for.
            if (status == 429) {
                return Mono.error(rateLimitGuard.pausedError());
            }

            if (status == 401) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Spotify rejected this connection. Connect Spotify and try again."
                ));
            }

            if (status == 403) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Spotify refused the search request for this account."
                ));
            }

            return Mono.just(LookupResult.unknown());
        }

        log.warn("Spotify search failed | query={} | error={}", query, error.getClass().getSimpleName());
        return Mono.just(LookupResult.unknown());
    }

    private SpotifyMatchScore scoreSpotifyResult(
            String spotifyTrackName,
            List<String> spotifyArtists,
            String expectedArtist,
            String expectedTrack
    ) {
        int trackScore = scoreTrackName(spotifyTrackName, expectedTrack);
        int artistScore = scoreArtistNames(spotifyArtists, expectedArtist);
        int totalScore = trackScore + artistScore;

        boolean valid = trackScore >= MIN_TRACK_MATCH_SCORE
                && artistScore >= MIN_ARTIST_MATCH_SCORE
                && totalScore >= MIN_TOTAL_MATCH_SCORE;

        return new SpotifyMatchScore(
                trackScore,
                artistScore,
                totalScore,
                valid
        );
    }

    private int scoreTrackName(String spotifyTrackName, String expectedTrack) {
        String spotifyTrack = TrackText.searchTitle(null, spotifyTrackName);

        if (spotifyTrack.isBlank() || expectedTrack.isBlank()) {
            return 0;
        }

        if (spotifyTrack.equals(expectedTrack)) {
            return 100;
        }

        if (spotifyTrack.contains(expectedTrack) || expectedTrack.contains(spotifyTrack)) {
            return 75;
        }

        double coverage = tokenCoverage(expectedTrack, spotifyTrack);

        if (coverage >= 0.75) {
            return 65;
        }

        if (coverage >= 0.50) {
            return 50;
        }

        if (coverage >= 0.35) {
            return 35;
        }

        return 0;
    }

    private int scoreArtistNames(List<String> spotifyArtists, String expectedArtist) {
        String cleanExpectedArtist = TrackText.simplify(expectedArtist);

        if (cleanExpectedArtist.isBlank() || spotifyArtists == null || spotifyArtists.isEmpty()) {
            return 0;
        }

        List<String> cleanSpotifyArtists = spotifyArtists.stream()
                .map(TrackText::simplify)
                .filter(value -> !value.isBlank())
                .toList();

        if (cleanSpotifyArtists.isEmpty()) {
            return 0;
        }

        String combinedSpotifyArtists = String.join(" ", cleanSpotifyArtists)
                .replaceAll("\\s+", " ")
                .trim();

        int bestScore = 0;

        if (combinedSpotifyArtists.equals(cleanExpectedArtist)) {
            bestScore = Math.max(bestScore, 100);
        }

        for (String spotifyArtist : cleanSpotifyArtists) {
            if (spotifyArtist.equals(cleanExpectedArtist)) {
                bestScore = Math.max(bestScore, 100);
                continue;
            }

            if (strongContainsMatch(spotifyArtist, cleanExpectedArtist)) {
                bestScore = Math.max(bestScore, 75);
                continue;
            }

            if (strongContainsMatch(cleanExpectedArtist, spotifyArtist)) {
                bestScore = Math.max(bestScore, 75);
            }
        }

        double combinedCoverage = tokenCoverage(cleanExpectedArtist, combinedSpotifyArtists);

        if (combinedCoverage >= 0.90) {
            bestScore = Math.max(bestScore, 95);
        } else if (combinedCoverage >= 0.70) {
            bestScore = Math.max(bestScore, 80);
        } else if (combinedCoverage >= 0.50) {
            bestScore = Math.max(bestScore, 60);
        } else if (combinedCoverage >= 0.35) {
            bestScore = Math.max(bestScore, 40);
        }

        return bestScore;
    }

    private boolean strongContainsMatch(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }

        if (left.length() < 4 || right.length() < 4) {
            return false;
        }

        return left.contains(right) || right.contains(left);
    }

    private double tokenCoverage(String expected, String actual) {
        Set<String> expectedTokens = meaningfulTokens(expected);
        Set<String> actualTokens = meaningfulTokens(actual);

        if (expectedTokens.isEmpty() || actualTokens.isEmpty()) {
            return 0.0;
        }

        int matches = 0;

        for (String expectedToken : expectedTokens) {
            if (actualTokens.contains(expectedToken)) {
                matches++;
            }
        }

        return matches / (double) expectedTokens.size();
    }

    private Set<String> meaningfulTokens(String value) {
        String cleanValue = TrackText.simplify(value);

        if (cleanValue.isBlank()) {
            return Set.of();
        }

        String[] tokens = cleanValue.split("\\s+");
        Set<String> result = new HashSet<>();

        for (String token : tokens) {
            boolean shortLatinToken = token.length() < 3 && token.chars().allMatch(c -> c < 128);

            if (shortLatinToken) {
                continue;
            }

            if (isWeakToken(token)) {
                continue;
            }

            result.add(token);
        }

        return result;
    }

    private boolean isWeakToken(String token) {
        return token.equals("the")
                || token.equals("and")
                || token.equals("with")
                || token.equals("feat")
                || token.equals("ft")
                || token.equals("prod")
                || token.equals("official")
                || token.equals("video")
                || token.equals("audio")
                || token.equals("music")
                || token.equals("remix")
                || token.equals("remaster")
                || token.equals("version");
    }

    private String cacheKey(
            String requiredSpotifyArtistId,
            String artistName,
            String trackName
    ) {
        return requiredSpotifyArtistId
                + "|"
                + TrackText.simplify(artistName)
                + "|"
                + TrackText.searchTitle(artistName, trackName);
    }

    /** spotifyUri is null when there is no match; searchedSpotify is false for remembered answers. */
    public record TrackMatch(String spotifyUri, boolean searchedSpotify) {
        static TrackMatch remembered(String spotifyUri) {
            return new TrackMatch(spotifyUri, false);
        }

        static TrackMatch searched(String spotifyUri) {
            return new TrackMatch(spotifyUri, true);
        }
    }

    private record LookupResult(
            String uri,
            boolean definitiveMiss
    ) {
        static LookupResult found(String uri) {
            return new LookupResult(uri, false);
        }

        static LookupResult miss() {
            return new LookupResult(null, true);
        }

        static LookupResult unknown() {
            return new LookupResult(null, false);
        }

        boolean hasUri() {
            return uri != null && !uri.isBlank();
        }
    }

    private record SpotifyMatchScore(
            int trackScore,
            int artistScore,
            int totalScore,
            boolean valid
    ) {
    }

    private record ScoredSpotifyTrack(
            String uri,
            int totalScore,
            int trackScore,
            int artistScore,
            String spotifyTrackName,
            List<String> spotifyArtists,
            List<String> spotifyArtistIds
    ) {
    }
}