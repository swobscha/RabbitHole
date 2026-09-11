package spotifybot.provider;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spotifybot.config.LastFmProperties;
import spotifybot.model.PlaylistGenerationRequest;
import spotifybot.model.TrackCandidate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LastFmMusicProvider implements MusicApiProvider {

    private static final int MIN_SIMILAR_ARTIST_LIMIT = 100;
    private static final int MAX_SIMILAR_ARTIST_LIMIT = 2000;
    private static final int MAX_ARTISTS_TO_EXPLORE = 500;
    private static final int TRACKS_PER_PAGE = 10;

    private static final long RATE_LIMIT_BACKOFF_MS = 60_000;

    private final LastFmProperties properties;
    private final WebClient webClient;

    private final Map<String, List<ArtistSeed>> similarArtistCache = new ConcurrentHashMap<>();
    private final Map<String, ArtistSeed> artistInfoCache = new ConcurrentHashMap<>();
    private final Map<String, List<IndexedTrackNode>> topTrackPageCache = new ConcurrentHashMap<>();
    private final Map<String, TrackInfo> trackInfoCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> trackTagsCache = new ConcurrentHashMap<>();

    private volatile long rateLimitedUntilMs = 0;

    public LastFmMusicProvider(LastFmProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl("https://ws.audioscrobbler.com")
                .build();
    }

    @Override
    public Flux<TrackCandidate> findCandidateTracks(PlaylistGenerationRequest request) {
        List<String> startingArtists = buildStartingArtists(request);

        if (startingArtists.isEmpty()) {
            return Flux.empty();
        }

        int similarArtistLimit = effectiveSimilarArtistLimit(request);
        int artistsToExplore = effectiveArtistsToExplore(request, similarArtistLimit);

        return Flux.fromIterable(startingArtists)
                .flatMap(artistName ->
                                Flux.concat(
                                        Mono.just(new ArtistSeed(artistName, 1.0, null, null, true, 0)),
                                        findSimilarArtists(artistName, similarArtistLimit)
                                ),
                        2
                )
                .filter(seed -> seed.name() != null && !seed.name().isBlank())
                .distinct(seed -> seed.name().toLowerCase())
                .flatMap(this::enrichArtistSeed, 4)
                .collectList()
                .flatMapMany(artistSeeds -> Flux.fromIterable(rankArtists(artistSeeds, request)))
                .take(artistsToExplore)
                .flatMap(artistSeed -> findTopTracksForArtist(artistSeed, request), 4)
                .distinct(track -> (track.artistName() + "|" + track.name()).toLowerCase());
    }

    private List<String> buildStartingArtists(PlaylistGenerationRequest request) {
        Set<String> artists = new LinkedHashSet<>();

        if (request.seedArtist() != null && !request.seedArtist().isBlank()) {
            artists.add(request.seedArtist().trim());
        }

        if (request.preferredArtists() != null) {
            for (String artist : request.preferredArtists()) {
                if (artist != null && !artist.isBlank()) {
                    artists.add(artist.trim());
                }
            }
        }

        return new ArrayList<>(artists);
    }

    private int effectiveSimilarArtistLimit(PlaylistGenerationRequest request) {
        int requestedLimit = request.safeDiscoveryLimit();

        return Math.max(
                MIN_SIMILAR_ARTIST_LIMIT,
                Math.min(MAX_SIMILAR_ARTIST_LIMIT, requestedLimit)
        );
    }

    private int effectiveArtistsToExplore(
            PlaylistGenerationRequest request,
            int similarArtistLimit
    ) {
        int playlistSize = request.playlistSize() <= 0 ? 20 : request.playlistSize();

        int base = playlistSize * 10;

        if (request.avoidUsedTracks()) {
            base += playlistSize * 5;
        }

        if (request.nicheArtistScale() > 0.5) {
            base += playlistSize * 10;
        }

        if (request.nicheSongScale() > 0.5) {
            base += playlistSize * 4;
        }

        return Math.max(
                100,
                Math.min(
                        Math.min(similarArtistLimit, MAX_ARTISTS_TO_EXPLORE),
                        base
                )
        );
    }

    private List<Integer> trackPagesToFetch(PlaylistGenerationRequest request) {
        double nicheArtistScale = clamp(request.nicheArtistScale());
        double nicheSongScale = clamp(request.nicheSongScale());

        if (nicheArtistScale >= 0.75 && nicheSongScale >= 0.75) {
            return List.of(1, 3, 4, 5, 6, 7, 8);
        }

        if (nicheSongScale >= 0.85) {
            return List.of(1, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        }

        if (nicheSongScale >= 0.65) {
            return List.of(1, 3, 4, 5, 6, 7, 8);
        }

        if (nicheSongScale >= 0.35) {
            return List.of(1, 2, 3, 4);
        }

        return List.of(1);
    }

    private Flux<ArtistSeed> findSimilarArtists(String artistName, int limit) {
        String cacheKey = cleanKey(artistName) + "|limit:" + limit;

        List<ArtistSeed> cached = similarArtistCache.get(cacheKey);

        if (cached != null) {
            return Flux.fromIterable(cached);
        }

        if (isRateLimited()) {
            return Flux.empty();
        }

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/2.0/")
                        .queryParam("method", "artist.getsimilar")
                        .queryParam("artist", artistName)
                        .queryParam("limit", limit)
                        .queryParam("autocorrect", 1)
                        .queryParam("api_key", properties.getApiKey())
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMapMany(json -> {
                    if (hasLastFmError(json)) {
                        handleLastFmError(json);
                        return Flux.empty();
                    }

                    JsonNode artistsNode = json.path("similarartists").path("artist");

                    if (!artistsNode.isArray()) {
                        return Flux.empty();
                    }

                    List<ArtistSeed> artists = new ArrayList<>();
                    int[] rank = {1};

                    artistsNode.forEach(node -> {
                        String name = node.path("name").asText("");
                        double match = parseDouble(node.path("match").asText("0.5"), 0.5);
                        int currentRank = rank[0]++;

                        if (!name.isBlank()) {
                            artists.add(new ArtistSeed(name, match, null, null, false, currentRank));
                        }
                    });

                    similarArtistCache.put(cacheKey, artists);

                    return Flux.fromIterable(artists);
                })
                .onErrorResume(error -> {
                    handleRequestError(error);
                    return Flux.empty();
                });
    }

    private Mono<ArtistSeed> enrichArtistSeed(ArtistSeed seed) {
        String cacheKey = cleanKey(seed.name());

        ArtistSeed cached = artistInfoCache.get(cacheKey);

        if (cached != null) {
            return Mono.just(new ArtistSeed(
                    seed.name(),
                    seed.match(),
                    cached.listeners(),
                    cached.playcount(),
                    seed.startingArtist(),
                    seed.similarRank()
            ));
        }

        if (isRateLimited()) {
            return Mono.just(seed);
        }

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/2.0/")
                        .queryParam("method", "artist.getinfo")
                        .queryParam("artist", seed.name())
                        .queryParam("autocorrect", 1)
                        .queryParam("api_key", properties.getApiKey())
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    if (hasLastFmError(json)) {
                        handleLastFmError(json);
                        return seed;
                    }

                    JsonNode stats = json.path("artist").path("stats");

                    Long listeners = parseLong(stats.path("listeners").asText(""));
                    Long playcount = parseLong(stats.path("playcount").asText(""));

                    ArtistSeed enriched = new ArtistSeed(
                            seed.name(),
                            seed.match(),
                            listeners,
                            playcount,
                            seed.startingArtist(),
                            seed.similarRank()
                    );

                    artistInfoCache.put(cacheKey, enriched);

                    return enriched;
                })
                .onErrorResume(error -> {
                    handleRequestError(error);
                    return Mono.just(seed);
                });
    }

    private List<ArtistSeed> rankArtists(
            List<ArtistSeed> artistSeeds,
            PlaylistGenerationRequest request
    ) {
        double nicheArtistScale = clamp(request.nicheArtistScale());

        List<ArtistSeed> candidates = artistSeeds.stream()
                .filter(seed -> {
                    if (nicheArtistScale >= 0.75 && seed.startingArtist()) {
                        return false;
                    }

                    return true;
                })
                .toList();

        if (nicheArtistScale >= 0.65) {
            int minimumRank = minimumSimilarRankForNiche(nicheArtistScale);

            List<ArtistSeed> deeperCandidates = candidates.stream()
                    .filter(seed -> seed.similarRank() >= minimumRank)
                    .toList();

            int minimumUsableDeepArtists = Math.max(
                    30,
                    (request.playlistSize() <= 0 ? 20 : request.playlistSize()) * 2
            );

            if (deeperCandidates.size() >= minimumUsableDeepArtists) {
                candidates = deeperCandidates;
            }
        }

        return candidates.stream()
                .sorted(Comparator.<ArtistSeed>comparingDouble(
                        seed -> artistScore(seed, nicheArtistScale)
                ).reversed())
                .toList();
    }
    private int minimumSimilarRankForNiche(double nicheArtistScale) {
        if (nicheArtistScale >= 0.95) {
            return 180;
        }

        if (nicheArtistScale >= 0.85) {
            return 120;
        }

        if (nicheArtistScale >= 0.65) {
            return 60;
        }

        return 1;
    }
    private double artistScore(ArtistSeed seed, double nicheArtistScale) {
        double similarityScore = seed.match() * 100.0;

        Long artistSizeCount = usableCount(seed.listeners(), seed.playcount());

        double listenerNicheScore = inverseCountScore(artistSizeCount) * 100.0;

        double rankDepthScore = seed.similarRank() <= 0
                ? 0.0
                : clamp((seed.similarRank() - 1) / 350.0) * 100.0;

        double nicheScore;

        if (artistSizeCount == null) {
            nicheScore = rankDepthScore;
        } else {
            nicheScore = Math.max(listenerNicheScore, rankDepthScore * 0.85);
        }

        double similarityWeight = 1.0 - (0.92 * nicheArtistScale);
        double nicheWeight = 3.25 * nicheArtistScale;

        double startingArtistPenalty = seed.startingArtist()
                ? 220.0 * nicheArtistScale
                : 0.0;

        return (similarityScore * similarityWeight)
                + (nicheScore * nicheWeight)
                - startingArtistPenalty;
    }

    private Flux<TrackCandidate> findTopTracksForArtist(
            ArtistSeed artistSeed,
            PlaylistGenerationRequest request
    ) {
        return Flux.fromIterable(trackPagesToFetch(request))
                .flatMap(page -> fetchTopTrackPage(artistSeed, page), 2)
                .flatMap(indexedTrack ->
                                buildTrackCandidate(
                                        artistSeed,
                                        indexedTrack.trackNode(),
                                        indexedTrack.globalRank(),
                                        request
                                ),
                        4
                )
                .onErrorResume(error -> Flux.empty());
    }

    private Flux<IndexedTrackNode> fetchTopTrackPage(ArtistSeed artistSeed, int page) {
        String cacheKey = cleanKey(artistSeed.name()) + "|page:" + page + "|limit:" + TRACKS_PER_PAGE;

        List<IndexedTrackNode> cached = topTrackPageCache.get(cacheKey);

        if (cached != null) {
            return Flux.fromIterable(cached);
        }

        if (isRateLimited()) {
            return Flux.empty();
        }

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/2.0/")
                        .queryParam("method", "artist.gettoptracks")
                        .queryParam("artist", artistSeed.name())
                        .queryParam("limit", TRACKS_PER_PAGE)
                        .queryParam("page", page)
                        .queryParam("autocorrect", 1)
                        .queryParam("api_key", properties.getApiKey())
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMapMany(json -> {
                    if (hasLastFmError(json)) {
                        handleLastFmError(json);
                        return Flux.empty();
                    }

                    JsonNode tracksNode = json.path("toptracks").path("track");

                    if (!tracksNode.isArray()) {
                        return Flux.empty();
                    }

                    List<IndexedTrackNode> tracks = new ArrayList<>();

                    for (int i = 0; i < tracksNode.size(); i++) {
                        int globalRank = ((page - 1) * TRACKS_PER_PAGE) + i + 1;
                        tracks.add(new IndexedTrackNode(tracksNode.get(i), globalRank));
                    }

                    topTrackPageCache.put(cacheKey, tracks);

                    return Flux.fromIterable(tracks);
                })
                .onErrorResume(error -> {
                    handleRequestError(error);
                    return Flux.empty();
                });
    }

    private Mono<TrackCandidate> buildTrackCandidate(
            ArtistSeed artistSeed,
            JsonNode trackNode,
            int globalRank,
            PlaylistGenerationRequest request
    ) {
        String trackName = trackNode.path("name").asText("");
        String artistName = trackNode.path("artist").path("name").asText(artistSeed.name());

        if (trackName.isBlank() || artistName.isBlank()) {
            return Mono.empty();
        }

        int sourceScore = calculateSourceScore(globalRank, artistSeed.match());

        Mono<Set<String>> tagsMono = shouldFetchTags(request)
                ? findTopTagsForTrack(artistName, trackName)
                : Mono.just(Set.of());

        Mono<TrackInfo> trackInfoMono = shouldFetchTrackInfo(request)
                ? findTrackInfo(artistName, trackName)
                : Mono.just(new TrackInfo(null, null, "Unknown Album"));

        return Mono.zip(tagsMono, trackInfoMono)
                .map(tuple -> {
                    Set<String> tags = tuple.getT1();
                    TrackInfo trackInfo = tuple.getT2();

                    String albumName = trackInfo.albumName() == null || trackInfo.albumName().isBlank()
                            ? "Unknown Album"
                            : trackInfo.albumName();

                    return new TrackCandidate(
                            "lastfm-" + slugify(artistName + "-" + trackName),
                            trackName,
                            artistName,
                            artistName,
                            albumName,
                            sourceScore,
                            tags,
                            "lastfm",
                            null,
                            artistSeed.listeners(),
                            artistSeed.playcount(),
                            trackInfo.listeners(),
                            trackInfo.playcount(),
                            artistSeed.match()
                    );
                });
    }

    private boolean shouldFetchTags(PlaylistGenerationRequest request) {
        return request.preferredGenres() != null && !request.preferredGenres().isEmpty();
    }

    private boolean shouldFetchTrackInfo(PlaylistGenerationRequest request) {
        return clamp(request.nicheSongScale()) > 0.05;
    }

    private Mono<TrackInfo> findTrackInfo(String artistName, String trackName) {
        String cacheKey = cleanKey(artistName) + "|" + cleanKey(trackName);

        TrackInfo cached = trackInfoCache.get(cacheKey);

        if (cached != null) {
            return Mono.just(cached);
        }

        if (isRateLimited()) {
            return Mono.just(new TrackInfo(null, null, "Unknown Album"));
        }

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/2.0/")
                        .queryParam("method", "track.getinfo")
                        .queryParam("artist", artistName)
                        .queryParam("track", trackName)
                        .queryParam("autocorrect", 1)
                        .queryParam("api_key", properties.getApiKey())
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    if (hasLastFmError(json)) {
                        handleLastFmError(json);
                        return new TrackInfo(null, null, "Unknown Album");
                    }

                    JsonNode trackNode = json.path("track");

                    Long listeners = parseLong(trackNode.path("listeners").asText(""));
                    Long playcount = parseLong(trackNode.path("playcount").asText(""));
                    String albumName = trackNode.path("album").path("title").asText("");

                    TrackInfo info = new TrackInfo(listeners, playcount, albumName);
                    trackInfoCache.put(cacheKey, info);

                    return info;
                })
                .onErrorResume(error -> {
                    handleRequestError(error);
                    return Mono.just(new TrackInfo(null, null, "Unknown Album"));
                });
    }

    private Mono<Set<String>> findTopTagsForTrack(String artistName, String trackName) {
        String cacheKey = cleanKey(artistName) + "|" + cleanKey(trackName);

        Set<String> cached = trackTagsCache.get(cacheKey);

        if (cached != null) {
            return Mono.just(cached);
        }

        if (isRateLimited()) {
            return Mono.just(Set.of());
        }

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/2.0/")
                        .queryParam("method", "track.gettoptags")
                        .queryParam("artist", artistName)
                        .queryParam("track", trackName)
                        .queryParam("autocorrect", 1)
                        .queryParam("api_key", properties.getApiKey())
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    if (hasLastFmError(json)) {
                        handleLastFmError(json);
                        return Set.<String>of();
                    }

                    JsonNode tagsNode = json.path("toptags").path("tag");
                    Set<String> tags = new LinkedHashSet<>();

                    if (!tagsNode.isArray()) {
                        return tags;
                    }

                    tagsNode.forEach(tagNode -> {
                        if (tags.size() >= 6) {
                            return;
                        }

                        String tagName = tagNode.path("name").asText("");

                        if (!tagName.isBlank()) {
                            tags.add(tagName.toLowerCase());
                        }
                    });

                    trackTagsCache.put(cacheKey, tags);

                    return tags;
                })
                .onErrorResume(error -> {
                    handleRequestError(error);
                    return Mono.just(Set.of());
                });
    }

    private boolean hasLastFmError(JsonNode json) {
        return json != null && json.has("error");
    }

    private void handleLastFmError(JsonNode json) {
        int errorCode = json.path("error").asInt(-1);

        if (errorCode == 29) {
            rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS;
        }
    }

    private void handleRequestError(Throwable error) {
        if (error instanceof WebClientResponseException responseException) {
            if (responseException.getStatusCode().value() == 429) {
                rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS;
            }
        }
    }

    private boolean isRateLimited() {
        return System.currentTimeMillis() < rateLimitedUntilMs;
    }

    private int calculateSourceScore(int globalRank, double artistMatch) {
        int rankScore = Math.max(0, 100 - ((globalRank - 1) * 2));
        double adjustedScore = rankScore * artistMatch;

        return (int) Math.max(0, Math.min(100, adjustedScore));
    }

    private double inverseCountScore(Long count) {
        if (count == null || count <= 0) {
            return 0.0;
        }

        double log = Math.log10(count + 1);
        double score = 1.0 - (log / 7.0);

        return clamp(score);
    }

    private Long usableCount(Long primary, Long fallback) {
        if (primary != null && primary > 0) {
            return primary;
        }

        if (fallback != null && fallback > 0) {
            return fallback;
        }

        return null;
    }

    private double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private Long parseLong(String value) {
        try {
            if (value == null || value.isBlank()) {
                return null;
            }

            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String cleanKey(String value) {
        if (value == null) {
            return "";
        }

        return value.toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private String slugify(String value) {
        return value.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private record ArtistSeed(
            String name,
            double match,
            Long listeners,
            Long playcount,
            boolean startingArtist,
            int similarRank
    ) {
    }

    private record TrackInfo(
            Long listeners,
            Long playcount,
            String albumName
    ) {
    }

    private record IndexedTrackNode(
            JsonNode trackNode,
            int globalRank
    ) {
    }
}