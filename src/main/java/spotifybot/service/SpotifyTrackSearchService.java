package spotifybot.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import spotifybot.model.TrackCandidate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SpotifyTrackSearchService {

    private static final long DEFAULT_SPOTIFY_BACKOFF_SECONDS = 30;

    private static final int MIN_TRACK_MATCH_SCORE = 50;
    private static final int MIN_ARTIST_MATCH_SCORE = 55;
    private static final int MIN_TOTAL_MATCH_SCORE = 115;

    private final WebClient webClient;
    private final Map<String, String> uriCache = new ConcurrentHashMap<>();
    private final Set<String> missCache = ConcurrentHashMap.newKeySet();

    private volatile long spotifyRateLimitedUntilMs = 0;

    public SpotifyTrackSearchService() {
        this.webClient = WebClient.builder()
                .baseUrl("https://api.spotify.com/v1")
                .build();
    }

    public Mono<String> findSpotifyUri(String accessToken, TrackCandidate track) {
        return findSpotifyUri(accessToken, track, "");
    }

    public Mono<String> findSpotifyUri(
            String accessToken,
            TrackCandidate track,
            String requiredSpotifyArtistId
    ) {
        if (track.spotifyUri() != null && !track.spotifyUri().isBlank()) {
            return Mono.just(track.spotifyUri());
        }

        if (isSpotifyRateLimited()) {
            long secondsLeft = secondsUntilSpotifyReady();

            System.out.println(
                    "Spotify search skipped because rate limit cooldown is active."
                            + " Wait about "
                            + secondsLeft
                            + " seconds."
            );

            return Mono.error(new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Spotify is rate-limiting searches. Wait about "
                            + secondsLeft
                            + " seconds and try again."
            ));
        }

        String cleanRequiredArtistId = requiredSpotifyArtistId == null
                ? ""
                : requiredSpotifyArtistId.trim();

        String cacheKey = cacheKey(
                cleanRequiredArtistId,
                track.artistName(),
                track.name()
        );

        if (uriCache.containsKey(cacheKey)) {
            return Mono.just(uriCache.get(cacheKey));
        }

        if (missCache.contains(cacheKey)) {
            return Mono.empty();
        }

        return searchSpotifyOnce(
                accessToken,
                track.artistName(),
                track.name(),
                cleanRequiredArtistId
        )
                .flatMap(result -> {
                    if (result.hasUri()) {
                        uriCache.put(cacheKey, result.uri());
                        return Mono.just(result.uri());
                    }

                    if (result.definitiveMiss()) {
                        missCache.add(cacheKey);
                    }

                    System.out.println("Spotify could not resolve: " + track.artistName() + " - " + track.name());
                    return Mono.empty();
                });
    }

    private Mono<LookupResult> searchSpotifyOnce(
            String accessToken,
            String artistName,
            String trackName,
            String requiredSpotifyArtistId
    ) {
        String cleanArtist = cleanSearchText(artistName);
        String cleanTrack = cleanSearchText(trackName);

        if (cleanTrack.isBlank()) {
            return Mono.just(LookupResult.miss());
        }

        String query = cleanTrack + " " + cleanArtist;

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search")
                        .queryParam("q", query)
                        .queryParam("type", "track")
                        .queryParam("limit", 10)
                        .build())
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
                            System.out.println(
                                    "Spotify rejected wrong seed artist: "
                                            + artistName
                                            + " - "
                                            + trackName
                                            + " -> "
                                            + spotifyTrackName
                                            + " by "
                                            + spotifyArtists
                                            + " | spotifyArtistIds="
                                            + spotifyArtistIds
                                            + " | requiredSeedArtistId="
                                            + requiredSpotifyArtistId
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
                            System.out.println(
                                    "Spotify rejected weak match: "
                                            + artistName
                                            + " - "
                                            + trackName
                                            + " -> "
                                            + spotifyTrackName
                                            + " by "
                                            + spotifyArtists
                                            + " | trackScore="
                                            + matchScore.trackScore()
                                            + " artistScore="
                                            + matchScore.artistScore()
                                            + " total="
                                            + matchScore.totalScore()
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
                                System.out.println(
                                        "Spotify resolved: "
                                                + artistName
                                                + " - "
                                                + trackName
                                                + " -> "
                                                + result.spotifyTrackName()
                                                + " by "
                                                + result.spotifyArtists()
                                                + " | trackScore "
                                                + result.trackScore()
                                                + " | artistScore "
                                                + result.artistScore()
                                                + " | total "
                                                + result.totalScore()
                                );

                                return Mono.just(LookupResult.found(result.uri()));
                            })
                            .orElse(Mono.just(LookupResult.miss()));
                })
                .onErrorResume(error -> handleSpotifyError(error, query));
    }

    private Mono<LookupResult> handleSpotifyError(Throwable error, String query) {
        if (error instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();

            System.out.println(
                    "Spotify search error for query ["
                            + query
                            + "]: "
                            + status
                            + " "
                            + responseException.getResponseBodyAsString()
            );

            if (status == 429) {
                long retryAfterSeconds = responseException.getHeaders()
                        .getFirst("Retry-After") == null
                        ? DEFAULT_SPOTIFY_BACKOFF_SECONDS
                        : parseRetryAfter(responseException.getHeaders().getFirst("Retry-After"));

                spotifyRateLimitedUntilMs = System.currentTimeMillis() + (retryAfterSeconds * 1000);

                return Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Spotify is rate-limiting searches. Wait about "
                                + retryAfterSeconds
                                + " seconds and try again."
                ));
            }

            return Mono.just(LookupResult.unknown());
        }

        System.out.println("Spotify search error for query [" + query + "]: " + error.getMessage());
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
        String spotifyTrack = cleanSearchText(spotifyTrackName);

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
        String cleanExpectedArtist = cleanSearchText(expectedArtist);

        if (cleanExpectedArtist.isBlank() || spotifyArtists == null || spotifyArtists.isEmpty()) {
            return 0;
        }

        List<String> cleanSpotifyArtists = spotifyArtists.stream()
                .map(this::cleanSearchText)
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
        String cleanValue = cleanSearchText(value);

        if (cleanValue.isBlank()) {
            return Set.of();
        }

        String[] tokens = cleanValue.split("\\s+");
        Set<String> result = new HashSet<>();

        for (String token : tokens) {
            if (token.length() < 3) {
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

    private long parseRetryAfter(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return DEFAULT_SPOTIFY_BACKOFF_SECONDS;
        }
    }

    private boolean isSpotifyRateLimited() {
        return System.currentTimeMillis() < spotifyRateLimitedUntilMs;
    }

    private long secondsUntilSpotifyReady() {
        long remainingMs = spotifyRateLimitedUntilMs - System.currentTimeMillis();

        if (remainingMs <= 0) {
            return 0;
        }

        return Math.max(1, remainingMs / 1000);
    }

    private String cacheKey(
            String requiredSpotifyArtistId,
            String artistName,
            String trackName
    ) {
        return requiredSpotifyArtistId
                + "|"
                + cleanSearchText(artistName)
                + "|"
                + cleanSearchText(trackName);
    }

    private String cleanSearchText(String value) {
        if (value == null) {
            return "";
        }

        return value.toLowerCase()
                .replaceAll("♱", " ")
                .replaceAll("^[^-]{1,80}\\s+-\\s+", "")
                .replaceAll("\\[explicit\\]", "")
                .replaceAll("\\(explicit\\)", "")
                .replaceAll("\\[clean\\]", "")
                .replaceAll("\\(clean\\)", "")
                .replaceAll("\\([^)]*feat\\.?[^)]*\\)", "")
                .replaceAll("\\[[^]]*feat\\.?[^]]*]", "")
                .replaceAll("\\([^)]*ft\\.?[^)]*\\)", "")
                .replaceAll("\\[[^]]*ft\\.?[^]]*]", "")
                .replaceAll("\\([^)]*with[^)]*\\)", "")
                .replaceAll("\\[[^]]*with[^]]*]", "")
                .replaceAll("\\([^)]*prod\\.?[^)]*\\)", "")
                .replaceAll("\\[[^]]*prod\\.?[^]]*]", "")
                .replaceAll("\\([^)]*remaster[^)]*\\)", "")
                .replaceAll("\\[[^]]*remaster[^]]*]", "")
                .replaceAll("\\([^)]*remix[^)]*\\)", "")
                .replaceAll("\\[[^]]*remix[^]]*]", "")
                .replaceAll("\\([^)]*version[^)]*\\)", "")
                .replaceAll("\\[[^]]*version[^]]*]", "")
                .replaceAll("\\([^)]*music video[^)]*\\)", "")
                .replaceAll("\\[[^]]*music video[^]]*]", "")
                .replaceAll("\\([^)]*mv[^)]*\\)", "")
                .replaceAll("\\[[^]]*mv[^]]*]", "")
                .replaceAll("\\([^)]*live[^)]*\\)", "")
                .replaceAll("\\[[^]]*live[^]]*]", "")
                .replaceAll("\\s+live\\s+complexcon\\s+\\d{4}.*$", "")
                .replaceAll("\\s+complexcon\\s+\\d{4}.*$", "")
                .replaceAll("^\\d+\\.\\s*", "")
                .replaceAll("\\s+-\\s+explicit$", "")
                .replaceAll("\\s+-\\s+clean$", "")
                .replaceAll("\\s+mv$", "")
                .replaceAll("\\s+music\\s+video$", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
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