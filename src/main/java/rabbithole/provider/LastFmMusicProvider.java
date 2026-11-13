package rabbithole.provider;

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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import rabbithole.config.LastFmProperties;
import rabbithole.model.PlaylistGenerationRequest;
import rabbithole.model.TrackCandidate;
import rabbithole.model.WeightedTrack;
import rabbithole.service.TrackText;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class LastFmMusicProvider implements MusicApiProvider {

    private static final Logger log = LoggerFactory.getLogger(LastFmMusicProvider.class);

    private static final int MIN_SIMILAR_ARTIST_LIMIT = 100;
    private static final int MAX_SIMILAR_ARTIST_LIMIT = 2000;
    private static final int MAX_ARTISTS_TO_EXPLORE = 500;
    private static final int MAX_ARTIST_STAT_LOOKUPS = 600;
    private static final int TRACKS_PER_PAGE = 10;
    private static final int MAX_TAGS = 6;
    private static final int LASTFM_CONCURRENCY = 8;

    private static final int LASTFM_INVALID_PARAMETERS = 6;
    private static final int LASTFM_INVALID_API_KEY = 10;
    private static final int LASTFM_SUSPENDED_API_KEY = 26;
    private static final int LASTFM_RATE_LIMITED = 29;

    private static final String REJECTED_KEY_MESSAGE = "Last.fm rejected the API key. Check LASTFM_API_KEY.";

    private static final long RATE_LIMIT_BACKOFF_MS = 60_000;
    private static final long RECENT_FAILURE_WINDOW_MS = 30_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    // Last.fm's commonly cited guideline is 5 requests per second averaged over 5 minutes, so bursts are
    // allowed as long as the 5-minute average stays at or under 5 per second.
    private static final long MIN_REQUEST_SPACING_MS = 200;
    private static final long BURST_WINDOW_MS = 300_000;

    private static final Duration CACHE_LIFETIME = Duration.ofHours(24);

    private final LastFmProperties properties;
    private final WebClient webClient;

    private final Cache<String, List<ArtistSeed>> similarArtistCache = newCache(200);
    private final Cache<String, ArtistSeed> artistInfoCache = newCache(10_000);
    private final Cache<String, List<TopTrack>> topTracksCache = newCache(2_000);
    private final Cache<String, TrackInfo> trackInfoCache = newCache(5_000);
    private final Cache<String, Set<String>> artistTagsCache = newCache(5_000);

    private final AtomicLong nextRequestSlotMs = new AtomicLong();

    private volatile long rateLimitedUntilMs = 0;
    private volatile long lastFailureAtMs = 0;

    public LastFmMusicProvider(
            LastFmProperties properties,
            @Value("${LASTFM_API_URL:https://ws.audioscrobbler.com}") String apiUrl
    ) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .build();
    }

    private static <V> Cache<String, V> newCache(int maxEntries) {
        return Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(CACHE_LIFETIME)
                .build();
    }

    @Override
    public Flux<TrackCandidate> findCandidateTracks(PlaylistGenerationRequest request) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return Flux.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Last.fm is not configured. Set LASTFM_API_KEY."
            ));
        }

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
                .distinct(seed -> seed.name().toLowerCase(Locale.ROOT))
                .collectList()
                .flatMapMany(artistSeeds -> selectArtistsToExplore(artistSeeds, request, artistsToExplore))
                .flatMap(artistSeed -> findTopTracksForArtist(artistSeed, request), LASTFM_CONCURRENCY)
                .distinct(track -> (track.artistName() + "|" + track.name()).toLowerCase(Locale.ROOT))
                .switchIfEmpty(Flux.defer(this::errorIfLastFmWasUnavailable));
    }

    /** Only look up listener counts before ranking when the ranking actually uses them. */
    private Flux<ArtistSeed> selectArtistsToExplore(
            List<ArtistSeed> artistSeeds,
            PlaylistGenerationRequest request,
            int artistsToExplore
    ) {
        if (clamp(request.nicheArtistScale()) > 0) {
            return Flux.fromIterable(artistSeeds)
                    .take(MAX_ARTIST_STAT_LOOKUPS)
                    .flatMap(this::enrichArtistSeed, LASTFM_CONCURRENCY)
                    .collectList()
                    .flatMapMany(enriched -> Flux.fromIterable(rankArtists(enriched, request)))
                    .take(artistsToExplore);
        }

        // Listener counts don't affect this ranking, so they're fetched later, only for the chosen tracks.
        return Flux.fromIterable(rankArtists(artistSeeds, request))
                .take(artistsToExplore);
    }

    @Override
    public Mono<List<WeightedTrack>> addArtistDetails(List<WeightedTrack> tracks) {
        Set<String> artistsWithoutDetails = tracks.stream()
                .map(WeightedTrack::track)
                .filter(track -> "lastfm".equals(track.sourceApi()))
                .filter(track -> track.artistListeners() == null && track.artistPlaycount() == null)
                .map(TrackCandidate::artistName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (artistsWithoutDetails.isEmpty()) {
            return Mono.just(tracks);
        }

        return Flux.fromIterable(artistsWithoutDetails)
                .flatMap(name -> enrichArtistSeed(new ArtistSeed(name, 0, null, null, false, 0)), LASTFM_CONCURRENCY)
                .collectMap(ArtistSeed::name, seed -> seed)
                .map(details -> tracks.stream()
                        .map(weightedTrack -> withArtistDetails(weightedTrack, details.get(weightedTrack.track().artistName())))
                        .toList());
    }

    private WeightedTrack withArtistDetails(WeightedTrack weightedTrack, ArtistSeed details) {
        TrackCandidate track = weightedTrack.track();

        if (details == null || (details.listeners() == null && details.playcount() == null)) {
            return weightedTrack;
        }

        return new WeightedTrack(
                new TrackCandidate(
                        track.id(),
                        track.name(),
                        track.artistName(),
                        track.artistId(),
                        track.albumName(),
                        track.popularity(),
                        track.genres(),
                        track.sourceApi(),
                        track.spotifyUri(),
                        details.listeners(),
                        details.playcount(),
                        track.trackListeners(),
                        track.trackPlaycount(),
                        track.artistSimilarity()
                ),
                weightedTrack.score(),
                weightedTrack.reasons()
        );
    }

    private Flux<TrackCandidate> errorIfLastFmWasUnavailable() {
        if (isRateLimited()) {
            return Flux.error(new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Last.fm is rate-limiting requests. Wait about a minute and try again."
            ));
        }

        if (System.currentTimeMillis() - lastFailureAtMs < RECENT_FAILURE_WINDOW_MS) {
            return Flux.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Last.fm could not be reached. Try again in a moment."
            ));
        }

        return Flux.empty();
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

        List<ArtistSeed> cached = similarArtistCache.getIfPresent(cacheKey);

        if (cached != null) {
            return Flux.fromIterable(cached);
        }

        if (isRateLimited()) {
            return Flux.empty();
        }

        return callLastFm("artist.getsimilar", Map.of("artist", artistName, "limit", String.valueOf(limit)))
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

        ArtistSeed cached = artistInfoCache.getIfPresent(cacheKey);

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

        return callLastFm("artist.getinfo", Map.of("artist", seed.name()))
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
        List<Integer> pages = trackPagesToFetch(request);
        Set<Integer> wantedPages = new HashSet<>(pages);
        int trackLimit = Collections.max(pages) * TRACKS_PER_PAGE;

        Mono<Set<String>> artistTags = shouldFetchTags(request)
                ? findTopTagsForArtist(artistSeed.name())
                : Mono.just(Set.of());

        return artistTags
                .flatMapMany(tags -> fetchTopTracks(artistSeed, trackLimit)
                        .filter(track -> wantedPages.contains(pageOfRank(track.globalRank())))
                        .flatMap(track -> buildTrackCandidate(artistSeed, track, tags, request), 4)
                )
                .onErrorResume(
                        error -> !(error instanceof ResponseStatusException),
                        error -> {
                            handleRequestError(error);
                            return Flux.empty();
                        }
                );
    }

    private int pageOfRank(int globalRank) {
        return ((globalRank - 1) / TRACKS_PER_PAGE) + 1;
    }

    /** One request covers every wanted page, and each track already includes its listener counts. */
    private Flux<TopTrack> fetchTopTracks(ArtistSeed artistSeed, int limit) {
        String cacheKey = cleanKey(artistSeed.name()) + "|limit:" + limit;

        List<TopTrack> cached = topTracksCache.getIfPresent(cacheKey);

        if (cached != null) {
            return Flux.fromIterable(cached);
        }

        if (isRateLimited()) {
            return Flux.empty();
        }

        return callLastFm("artist.gettoptracks", Map.of("artist", artistSeed.name(), "limit", String.valueOf(limit)))
                .flatMapMany(json -> {
                    if (hasLastFmError(json)) {
                        handleLastFmError(json);
                        return Flux.empty();
                    }

                    JsonNode tracksNode = json.path("toptracks").path("track");

                    if (!tracksNode.isArray()) {
                        return Flux.empty();
                    }

                    List<TopTrack> tracks = new ArrayList<>();

                    for (int i = 0; i < tracksNode.size(); i++) {
                        JsonNode trackNode = tracksNode.get(i);

                        tracks.add(new TopTrack(
                                trackNode.path("name").asText(""),
                                trackNode.path("artist").path("name").asText(artistSeed.name()),
                                parseLong(trackNode.path("listeners").asText("")),
                                parseLong(trackNode.path("playcount").asText("")),
                                i + 1
                        ));
                    }

                    topTracksCache.put(cacheKey, tracks);

                    return Flux.fromIterable(tracks);
                })
                .onErrorResume(error -> {
                    handleRequestError(error);
                    return Flux.empty();
                });
    }

    private Mono<TrackCandidate> buildTrackCandidate(
            ArtistSeed artistSeed,
            TopTrack topTrack,
            Set<String> artistTags,
            PlaylistGenerationRequest request
    ) {
        String trackName = topTrack.name();
        String artistName = topTrack.artistName();

        if (trackName.isBlank() || artistName.isBlank()) {
            return Mono.empty();
        }

        int sourceScore = calculateSourceScore(topTrack.globalRank(), artistSeed.match());

        boolean statsMissing = topTrack.listeners() == null && topTrack.playcount() == null;

        Mono<TrackInfo> trackInfoMono = statsMissing && shouldFetchTrackInfo(request)
                ? findTrackInfo(artistName, trackName)
                : Mono.just(new TrackInfo(topTrack.listeners(), topTrack.playcount(), "Unknown Album"));

        return trackInfoMono
                .map(trackInfo -> {
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
                            artistTags,
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

        TrackInfo cached = trackInfoCache.getIfPresent(cacheKey);

        if (cached != null) {
            return Mono.just(cached);
        }

        if (isRateLimited()) {
            return Mono.just(new TrackInfo(null, null, "Unknown Album"));
        }

        return callLastFm("track.getinfo", Map.of("artist", artistName, "track", trackName))
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

    /** Tags are fetched per artist because per-track lookups cost one request for every candidate. */
    private Mono<Set<String>> findTopTagsForArtist(String artistName) {
        String cacheKey = cleanKey(artistName);

        Set<String> cached = artistTagsCache.getIfPresent(cacheKey);

        if (cached != null) {
            return Mono.just(cached);
        }

        if (isRateLimited()) {
            return Mono.just(Set.of());
        }

        return callLastFm("artist.gettoptags", Map.of("artist", artistName))
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
                        if (tags.size() >= MAX_TAGS) {
                            return;
                        }

                        String tagName = tagNode.path("name").asText("");

                        if (!tagName.isBlank()) {
                            tags.add(tagName.toLowerCase(Locale.ROOT));
                        }
                    });

                    artistTagsCache.put(cacheKey, tags);

                    return tags;
                })
                .onErrorResume(error -> {
                    handleRequestError(error);
                    return Mono.just(Set.of());
                });
    }

    private Mono<JsonNode> callLastFm(String method, Map<String, String> parameters) {
        return Mono.defer(() -> {
            Map<String, Object> uriVariables = new HashMap<>(parameters);
            uriVariables.put("api_key", properties.getApiKey());

            Mono<JsonNode> request = webClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/2.0/")
                                .queryParam("method", method)
                                .queryParam("autocorrect", 1)
                                .queryParam("format", "json")
                                .queryParam("api_key", "{api_key}");

                        parameters.keySet().forEach(name -> uriBuilder.queryParam(name, "{" + name + "}"));

                        return uriBuilder.build(uriVariables);
                    })
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(REQUEST_TIMEOUT);

            long waitMs = reserveRequestSlot();

            return waitMs > 0 ? Mono.delay(Duration.ofMillis(waitMs)).then(request) : request;
        });
    }

    private long reserveRequestSlot() {
        long now = System.currentTimeMillis();
        long slot = nextRequestSlotMs.updateAndGet(
                previous -> Math.max(previous, now - BURST_WINDOW_MS) + MIN_REQUEST_SPACING_MS
        );

        return slot - now;
    }

    private boolean hasLastFmError(JsonNode json) {
        return json != null && json.has("error");
    }

    private void handleLastFmError(JsonNode json) {
        int errorCode = json.path("error").asInt(-1);

        if (errorCode == LASTFM_INVALID_API_KEY || errorCode == LASTFM_SUSPENDED_API_KEY) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, REJECTED_KEY_MESSAGE);
        }

        if (errorCode == LASTFM_RATE_LIMITED) {
            log.warn("Last.fm rate limit reached; pausing requests for {} ms.", RATE_LIMIT_BACKOFF_MS);
            rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS;
            return;
        }

        if (errorCode != LASTFM_INVALID_PARAMETERS) {
            log.warn("Last.fm returned error code {}.", errorCode);
            lastFailureAtMs = System.currentTimeMillis();
        }
    }

    private void handleRequestError(Throwable error) {
        if (error instanceof ResponseStatusException statusException) {
            throw statusException;
        }

        if (error instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();

            if (status == 403) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, REJECTED_KEY_MESSAGE);
            }

            if (status == 429) {
                log.warn("Last.fm rate limit reached; pausing requests for {} ms.", RATE_LIMIT_BACKOFF_MS);
                rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS;
                return;
            }

            log.warn("Last.fm request failed with status {}.", status);
        } else {
            // The exception message can include the request URL, which contains the API key.
            log.warn("Last.fm request failed: {}", error.getClass().getSimpleName());
        }

        lastFailureAtMs = System.currentTimeMillis();
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

        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String slugify(String value) {
        return TrackText.simplify(value).replace(' ', '-');
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

    private record TopTrack(
            String name,
            String artistName,
            Long listeners,
            Long playcount,
            int globalRank
    ) {
    }
}
