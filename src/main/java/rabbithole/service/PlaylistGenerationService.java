package rabbithole.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import rabbithole.model.PlaylistGenerationRequest;
import rabbithole.model.PlaylistGenerationResult;
import rabbithole.model.PlaylistReplaceTrackRequest;
import rabbithole.model.SpotifySession;
import rabbithole.model.TrackCandidate;
import rabbithole.model.WeightedTrack;
import rabbithole.provider.MusicApiProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PlaylistGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PlaylistGenerationService.class);

    private static final int DISCOVERY_BATCH_SIZE = 100;
    private static final int MAX_DISCOVERY_ROUNDS = 20;
    private static final int MIN_SPOTIFY_LOOKUP_ATTEMPTS = 60;
    private static final int SPOTIFY_LOOKUP_ATTEMPTS_PER_TRACK = 3;
    private static final int SPOTIFY_LOOKUP_CONCURRENCY = 5;

    private static final int MIN_PLAYLIST_SIZE = 1;
    private static final int MAX_PLAYLIST_SIZE = 50;
    private static final int MAX_ARTIST_NAME_LENGTH = 100;
    private static final int MAX_PLAYLIST_NAME_LENGTH = 100;
    private static final int MAX_FILTER_ITEMS = 25;
    private static final int MAX_FILTER_ITEM_LENGTH = 100;
    private static final int MAX_GENERATION_SEED_LENGTH = 120;

    private final List<MusicApiProvider> providers;
    private final TrackScoringService trackScoringService;
    private final PlaylistHistoryService playlistHistoryService;
    private final TrackKeyService trackKeyService;
    private final SpotifyAuthService spotifyAuthService;
    private final SpotifyTrackSearchService spotifyTrackSearchService;
    private final RankingDebugService rankingDebugService;

    public PlaylistGenerationService(
            List<MusicApiProvider> providers,
            TrackScoringService trackScoringService,
            PlaylistHistoryService playlistHistoryService,
            TrackKeyService trackKeyService,
            SpotifyAuthService spotifyAuthService,
            SpotifyTrackSearchService spotifyTrackSearchService,
            RankingDebugService rankingDebugService
    ) {
        this.providers = providers;
        this.trackScoringService = trackScoringService;
        this.playlistHistoryService = playlistHistoryService;
        this.trackKeyService = trackKeyService;
        this.spotifyAuthService = spotifyAuthService;
        this.spotifyTrackSearchService = spotifyTrackSearchService;
        this.rankingDebugService = rankingDebugService;
    }

    public Mono<PlaylistGenerationResult> generatePlaylist(
            Optional<SpotifySession> session,
            PlaylistGenerationRequest request
    ) {
        validateGenerationRequest(request);

        int playlistSize = request.playlistSize();
        int lookupAttempts = lookupAttemptsFor(playlistSize);

        log.info(
                "Generation started | seedArtist={} | requestedSize={} | maxSpotifyLookups={} | popularity={} | similarity={} | avoidUsedTracks={}",
                request.seedArtist(),
                playlistSize,
                lookupAttempts,
                request.nicheSongScale(),
                request.safeSimilarityScale(),
                request.avoidUsedTracks()
        );

        return spotifyAuthService.getAccessToken(session)
                .flatMap(accessToken ->
                        loadUsedTrackKeys(session, request)
                                .flatMap(usedTrackKeys -> {
                                    ValidationState state = new ValidationState(
                                            playlistSize,
                                            maxTracksPerArtist(request, playlistSize),
                                            lookupAttempts
                                    );

                                    return generateWithExpansion(
                                            request,
                                            usedTrackKeys,
                                            accessToken,
                                            1,
                                            state
                                    ).doOnError(error ->
                                            printGenerationErrorSummary(
                                                    request,
                                                    state.currentRound,
                                                    state,
                                                    error
                                            )
                                    );
                                })
                );
    }

    public Mono<WeightedTrack> replaceTrack(Optional<SpotifySession> session, PlaylistReplaceTrackRequest request) {
        validateReplaceTrackRequest(request);

        PlaylistGenerationRequest original = request.generationRequest();

        PlaylistGenerationRequest replacementRequest = new PlaylistGenerationRequest(
                original.seedArtist(),
                original.seedSpotifyArtistId(),
                original.playlistName(),
                original.playlistSize(),
                original.restrictedArtists(),
                original.preferredArtists(),
                original.preferredGenres(),
                original.randomness(),
                UUID.randomUUID().toString(),
                original.avoidUsedTracks(),
                original.nicheArtistScale(),
                original.nicheSongScale(),
                original.safeSimilarityScale(),
                original.safeDiscoveryLimit()
        );

        log.info(
                "Replacement started | seedArtist={} | currentTracks={}",
                original.seedArtist(),
                request.safeCurrentTracks().size()
        );

        ValidationState state = new ValidationState(
                1,
                maxTracksPerArtist(original, original.playlistSize()),
                lookupAttemptsFor(original.playlistSize())
        );

        blockCurrentTracks(state, request);

        return spotifyAuthService.getAccessToken(session)
                .flatMap(accessToken ->
                        loadUsedTrackKeys(session, replacementRequest)
                                .flatMap(usedTrackKeys -> generateWithExpansion(
                                        replacementRequest,
                                        usedTrackKeys,
                                        accessToken,
                                        1,
                                        state
                                ))
                )
                .map(result -> result.tracks()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Could not find a fresh replacement track. Try changing advanced settings or generating again."
                        ))
                )
                .doOnNext(replacement -> log.info(
                        "Replacement found | artist={} | track={} | spotifyUri={}",
                        replacement.track().artistName(),
                        replacement.track().name(),
                        replacement.track().spotifyUri()
                ));
    }

    private int lookupAttemptsFor(int playlistSize) {
        return Math.max(MIN_SPOTIFY_LOOKUP_ATTEMPTS, playlistSize * SPOTIFY_LOOKUP_ATTEMPTS_PER_TRACK);
    }

    private void blockCurrentTracks(ValidationState state, PlaylistReplaceTrackRequest request) {
        String replacedKey = trackKey(request.trackToReplace());

        state.attemptedKeys.add(replacedKey);

        for (WeightedTrack current : request.safeCurrentTracks()) {
            String key = trackKey(current);
            String uri = spotifyUri(current);

            state.attemptedKeys.add(key);

            if (!uri.isBlank()) {
                state.seenSpotifyUris.add(uri);
            }

            if (!key.equals(replacedKey) && current != null && current.track() != null) {
                state.artistCounts.merge(normalizeArtistName(current.track().artistName()), 1, Integer::sum);
            }
        }
    }

    private Mono<PlaylistGenerationResult> generateWithExpansion(
            PlaylistGenerationRequest originalRequest,
            Set<String> usedTrackKeys,
            String accessToken,
            int round,
            ValidationState state
    ) {
        int discoveryLimit = DISCOVERY_BATCH_SIZE * round;
        int attemptsBeforeRound = state.spotifyAttemptsMade();

        PlaylistGenerationRequest expandedRequest = withDiscoveryLimit(
                originalRequest,
                discoveryLimit
        );

        return generateSingleRound(expandedRequest, usedTrackKeys, accessToken, round, state)
                .flatMap(result -> {
                    boolean playlistIsFull = result.tracks().size() >= state.targetSize;
                    boolean reachedMaxRounds = round >= MAX_DISCOVERY_ROUNDS;
                    boolean attemptsExhausted = state.attemptsRemaining <= 0;
                    boolean noNewCandidates = !state.candidatePoolGrew
                            && state.spotifyAttemptsMade() == attemptsBeforeRound;

                    if (playlistIsFull || reachedMaxRounds || attemptsExhausted || noNewCandidates) {
                        String stopReason;

                        if (playlistIsFull) {
                            stopReason = "playlist full";
                        } else if (attemptsExhausted) {
                            stopReason = "Spotify lookup attempts exhausted";
                        } else if (noNewCandidates) {
                            stopReason = "no new candidates";
                        } else {
                            stopReason = "max discovery rounds reached";
                        }

                        printGenerationSummary(
                                originalRequest,
                                result,
                                round,
                                state,
                                stopReason
                        );

                        return withArtistDetails(result);
                    }

                    return generateWithExpansion(
                            originalRequest,
                            usedTrackKeys,
                            accessToken,
                            round + 1,
                            state
                    );
                });
    }

    private Mono<PlaylistGenerationResult> generateSingleRound(
            PlaylistGenerationRequest request,
            Set<String> usedTrackKeys,
            String accessToken,
            int round,
            ValidationState state
    ) {
        state.currentRound = round;

        return Flux.fromIterable(providers)
                .flatMap(provider -> provider.findCandidateTracks(request))
                .filter(track -> !isRestricted(track, request))
                .filter(track -> !isPreviouslyUsed(track, usedTrackKeys, request))
                .map(track -> trackScoringService.score(track, request))
                .collectList()
                .flatMap(weightedTracks -> {
                    List<WeightedTrack> sortedTracks = weightedTracks.stream()
                            .sorted(Comparator.comparingDouble(WeightedTrack::score).reversed())
                            .toList();

                    log.debug(
                            "Round {} | scoredCandidates={} | selectedSoFar={} | spotifyLookupsMade={} | spotifyLookupsLeft={} | discoveryLimit={}",
                            round,
                            sortedTracks.size(),
                            state.selectedTracks.size(),
                            state.spotifyAttemptsMade(),
                            state.attemptsRemaining,
                            request.safeDiscoveryLimit()
                    );

                    rankingDebugService.writeCandidateRankingDebug(
                            request,
                            round,
                            sortedTracks
                    );

                    return validateSpotifyCandidates(
                            request,
                            sortedTracks,
                            accessToken,
                            round,
                            state
                    );
                })
                .map(tracks -> new PlaylistGenerationResult(
                        request.playlistName(),
                        request.seedArtist(),
                        tracks
                ));
    }

    private Mono<List<WeightedTrack>> validateSpotifyCandidates(
            PlaylistGenerationRequest request,
            List<WeightedTrack> sortedTracks,
            String accessToken,
            int round,
            ValidationState state
    ) {
        List<WeightedTrack> uniqueSortedTracks = dedupeCandidatesBeforeSpotify(sortedTracks);

        state.candidatePoolGrew = uniqueSortedTracks.size() > state.lastCandidatePoolSize;
        state.lastCandidatePoolSize = uniqueSortedTracks.size();

        log.debug("Round {} | uniqueCandidates={}", round, uniqueSortedTracks.size());

        // Several lookups run at once, but their results are applied in ranking order,
        // so the best-ranked matches still win.
        return Flux.fromIterable(uniqueSortedTracks)
                .mapNotNull(weightedTrack -> reserveLookup(request, weightedTrack, state))
                .flatMapSequential(
                        lookup -> spotifyTrackSearchService.findSpotifyUri(
                                        accessToken,
                                        lookup.weightedTrack().track(),
                                        lookup.requiredSpotifyArtistId()
                                )
                                .map(lookup::withSpotifyUri)
                                .defaultIfEmpty(lookup),
                        SPOTIFY_LOOKUP_CONCURRENCY
                )
                .doOnNext(lookup -> applyLookup(lookup, state))
                .takeUntil(ignored -> state.isFull())
                .then(Mono.fromSupplier(() -> {
                    log.debug(
                            "Round {} complete | selected={} | lookupsMade={} | lookupsLeft={} | resolved={} | misses={} | duplicateSpotifyUris={} | artistCapSkips={}",
                            round,
                            state.selectedTracks.size(),
                            state.spotifyAttemptsMade(),
                            state.attemptsRemaining,
                            state.spotifyResolved,
                            state.spotifyMissed,
                            state.duplicateSpotifyUriResults,
                            state.artistCapSkips
                    );

                    rankingDebugService.writeSelectedRankingDebug(
                            request,
                            round,
                            state.selectedTracks
                    );

                    return List.copyOf(state.selectedTracks);
                }));
    }

    /** Decides whether a candidate is worth a Spotify lookup, and reserves its slot if so. */
    private SpotifyLookup reserveLookup(
            PlaylistGenerationRequest request,
            WeightedTrack weightedTrack,
            ValidationState state
    ) {
        synchronized (state) {
            if (state.isFull() || state.attemptsRemaining <= 0) {
                return null;
            }

            String key = trackKey(weightedTrack);

            if (state.attemptedKeys.contains(key) || state.selectedKeys.contains(key)) {
                state.duplicateCandidateSkips++;
                return null;
            }

            String artistKey = normalizeArtistName(weightedTrack.track().artistName());
            int artistTracks = state.artistCounts.getOrDefault(artistKey, 0)
                    + state.pendingArtistLookups.getOrDefault(artistKey, 0);

            if (artistTracks >= state.maxTracksPerArtist) {
                state.artistCapSkips++;
                return null;
            }

            state.attemptedKeys.add(key);
            state.attemptsRemaining--;
            state.pendingArtistLookups.merge(artistKey, 1, Integer::sum);

            return new SpotifyLookup(
                    weightedTrack,
                    key,
                    artistKey,
                    requiredSpotifyArtistIdForCandidate(request, weightedTrack.track()),
                    null
            );
        }
    }

    private void applyLookup(SpotifyLookup lookup, ValidationState state) {
        synchronized (state) {
            state.pendingArtistLookups.merge(lookup.artistKey(), -1, Integer::sum);

            if (lookup.spotifyUri() == null) {
                state.spotifyMissed++;
                return;
            }

            // A lookup that was already running when the playlist filled up.
            if (state.isFull()) {
                return;
            }

            if (state.seenSpotifyUris.contains(lookup.spotifyUri())) {
                state.duplicateSpotifyUriResults++;
                return;
            }

            if (state.artistCounts.getOrDefault(lookup.artistKey(), 0) >= state.maxTracksPerArtist) {
                state.artistCapSkips++;
                return;
            }

            state.selectedTracks.add(withSpotifyUri(lookup.weightedTrack(), lookup.spotifyUri()));
            state.selectedKeys.add(lookup.key());
            state.seenSpotifyUris.add(lookup.spotifyUri());
            state.artistCounts.merge(lookup.artistKey(), 1, Integer::sum);
            state.spotifyResolved++;
        }
    }

    /** Artist listener counts are fetched only for the artists that made the playlist. */
    private Mono<PlaylistGenerationResult> withArtistDetails(PlaylistGenerationResult result) {
        Mono<List<WeightedTrack>> tracks = Mono.just(result.tracks());

        for (MusicApiProvider provider : providers) {
            tracks = tracks.flatMap(provider::addArtistDetails);
        }

        return tracks
                .map(detailed -> new PlaylistGenerationResult(result.playlistName(), result.seedArtist(), detailed))
                .onErrorResume(error -> {
                    log.warn("Could not add artist details to the playlist: {}", error.getMessage());
                    return Mono.just(result);
                });
    }

    private void validateGenerationRequest(PlaylistGenerationRequest request) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Playlist generation request is missing."
            );
        }

        validateRequiredText(
                request.seedArtist(),
                "Seed artist",
                MAX_ARTIST_NAME_LENGTH
        );

        validateRequiredText(
                request.playlistName(),
                "Playlist name",
                MAX_PLAYLIST_NAME_LENGTH
        );

        if (request.playlistSize() < MIN_PLAYLIST_SIZE || request.playlistSize() > MAX_PLAYLIST_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Playlist size must be between 1 and 50."
            );
        }

        validateScale(request.randomness(), "Randomness");
        validateScale(request.nicheArtistScale(), "Niche artist scale");
        validateScale(request.nicheSongScale(), "Popularity scale");
        validateScale(request.safeSimilarityScale(), "Similarity scale");

        validateOptionalText(
                request.generationSeed(),
                "Generation seed",
                MAX_GENERATION_SEED_LENGTH
        );

        validateTextList(
                request.restrictedArtists(),
                "Restricted artists",
                MAX_FILTER_ITEMS,
                MAX_FILTER_ITEM_LENGTH
        );

        validateTextList(
                request.preferredArtists(),
                "Preferred artists",
                MAX_FILTER_ITEMS,
                MAX_FILTER_ITEM_LENGTH
        );

        validateTextList(
                request.preferredGenres(),
                "Preferred genres",
                MAX_FILTER_ITEMS,
                MAX_FILTER_ITEM_LENGTH
        );
    }

    private void validateReplaceTrackRequest(PlaylistReplaceTrackRequest request) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Replacement request is missing."
            );
        }

        if (request.generationRequest() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Replacement request is missing the original generation settings."
            );
        }

        validateGenerationRequest(request.generationRequest());

        if (request.safeCurrentTracks().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Replacement request is missing the current playlist tracks."
            );
        }

        if (request.safeCurrentTracks().size() > MAX_PLAYLIST_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Replacement request has too many current tracks."
            );
        }

        if (request.trackToReplace() == null || request.trackToReplace().track() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Replacement request is missing the track to replace."
            );
        }
    }

    private void validateRequiredText(String value, String fieldName, int maxLength) {
        if (value == null || value.trim().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " is required."
            );
        }

        if (value.trim().length() > maxLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " must be " + maxLength + " characters or fewer."
            );
        }
    }

    private void validateOptionalText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            return;
        }

        if (value.trim().length() > maxLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " must be " + maxLength + " characters or fewer."
            );
        }
    }

    private void validateTextList(
            List<String> values,
            String fieldName,
            int maxItems,
            int maxItemLength
    ) {
        if (values == null) {
            return;
        }

        if (values.size() > maxItems) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " can contain at most " + maxItems + " items."
            );
        }

        for (String value : values) {
            if (value == null) {
                continue;
            }

            if (value.trim().length() > maxItemLength) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        fieldName + " entries must be " + maxItemLength + " characters or fewer."
                );
            }
        }
    }

    private void validateScale(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " must be between 0.0 and 1.0."
            );
        }
    }

    private void printGenerationSummary(
            PlaylistGenerationRequest request,
            PlaylistGenerationResult result,
            int finalRound,
            ValidationState state,
            String stopReason
    ) {
        long elapsedMs = System.currentTimeMillis() - state.startedAtMs;

        log.info(
                "Generation finished | stopReason={} | seedArtist={} | requestedSize={} | returnedTracks={} | rounds={} "
                        + "| lookupsMade={} | resolved={} | misses={} | duplicateSpotifyUris={} | duplicateCandidateSkips={} "
                        + "| artistCapSkips={} | elapsedMs={}",
                stopReason,
                request.seedArtist(),
                state.targetSize,
                result.tracks().size(),
                finalRound,
                state.spotifyAttemptsMade(),
                state.spotifyResolved,
                state.spotifyMissed,
                state.duplicateSpotifyUriResults,
                state.duplicateCandidateSkips,
                state.artistCapSkips,
                elapsedMs
        );
    }

    private void printGenerationErrorSummary(
            PlaylistGenerationRequest request,
            int round,
            ValidationState state,
            Throwable error
    ) {
        long elapsedMs = System.currentTimeMillis() - state.startedAtMs;

        log.warn(
                "Generation failed | seedArtist={} | round={} | lookupsMade={} | resolved={} | misses={} | elapsedMs={} | error={}",
                request.seedArtist(),
                round,
                state.spotifyAttemptsMade(),
                state.spotifyResolved,
                state.spotifyMissed,
                elapsedMs,
                error.getMessage()
        );
    }

    private String requiredSpotifyArtistIdForCandidate(
            PlaylistGenerationRequest request,
            TrackCandidate track
    ) {
        String seedSpotifyArtistId = request.safeSeedSpotifyArtistId();

        if (seedSpotifyArtistId.isBlank()) {
            return "";
        }

        if (artistNameContainsSeedArtist(track.artistName(), request.seedArtist())) {
            return seedSpotifyArtistId;
        }

        return "";
    }

    private boolean artistNameContainsSeedArtist(String candidateArtistName, String seedArtistName) {
        String candidate = normalizeArtistName(candidateArtistName);
        String seed = normalizeArtistName(seedArtistName);

        if (candidate.isBlank() || seed.isBlank()) {
            return false;
        }

        return (" " + candidate + " ").contains(" " + seed + " ");
    }

    private List<WeightedTrack> dedupeCandidatesBeforeSpotify(List<WeightedTrack> sortedTracks) {
        Map<String, WeightedTrack> uniqueTracks = new LinkedHashMap<>();

        for (WeightedTrack weightedTrack : sortedTracks) {
            String key = trackKey(weightedTrack);

            if (key.isBlank()) {
                continue;
            }

            uniqueTracks.putIfAbsent(key, weightedTrack);
        }

        return new ArrayList<>(uniqueTracks.values());
    }

    private WeightedTrack withSpotifyUri(WeightedTrack weightedTrack, String spotifyUri) {
        TrackCandidate track = weightedTrack.track();

        TrackCandidate resolvedTrack = new TrackCandidate(
                track.id(),
                track.name(),
                track.artistName(),
                track.artistId(),
                track.albumName(),
                track.popularity(),
                track.genres(),
                track.sourceApi(),
                spotifyUri,
                track.artistListeners(),
                track.artistPlaycount(),
                track.trackListeners(),
                track.trackPlaycount(),
                track.artistSimilarity()
        );

        return new WeightedTrack(
                resolvedTrack,
                weightedTrack.score(),
                weightedTrack.reasons()
        );
    }

    private int maxTracksPerArtist(
            PlaylistGenerationRequest request,
            int playlistSize
    ) {
        double nicheArtistScale = Math.max(0, Math.min(1, request.nicheArtistScale()));
        double songStreamStrength = Math.abs((Math.max(0, Math.min(1, request.nicheSongScale())) - 0.5) * 2.0);
        double diversityScale = Math.max(nicheArtistScale, songStreamStrength);

        int hardCap = Math.max(1, playlistSize / 3);

        if (diversityScale >= 0.75) {
            return hardCap;
        }

        if (diversityScale >= 0.50) {
            return Math.max(hardCap, Math.max(1, playlistSize / 2));
        }

        if (diversityScale >= 0.25) {
            return Math.max(hardCap, Math.max(1, (int) Math.ceil(playlistSize * 0.65)));
        }

        return playlistSize;
    }

    private String trackKey(WeightedTrack weightedTrack) {
        if (weightedTrack == null || weightedTrack.track() == null) {
            return "";
        }

        String artistName = weightedTrack.track().artistName();
        String title = TrackText.identityTitle(artistName, weightedTrack.track().name());

        if (title.isBlank()) {
            return "";
        }

        return normalizeArtistName(artistName) + "|" + title;
    }

    private String spotifyUri(WeightedTrack weightedTrack) {
        if (weightedTrack == null
                || weightedTrack.track() == null
                || weightedTrack.track().spotifyUri() == null) {
            return "";
        }

        return weightedTrack.track().spotifyUri().trim();
    }

    private String normalizeArtistName(String artistName) {
        return TrackText.simplify(artistName);
    }

    private PlaylistGenerationRequest withDiscoveryLimit(
            PlaylistGenerationRequest request,
            int discoveryLimit
    ) {
        return new PlaylistGenerationRequest(
                request.seedArtist(),
                request.seedSpotifyArtistId(),
                request.playlistName(),
                request.playlistSize(),
                request.restrictedArtists(),
                request.preferredArtists(),
                request.preferredGenres(),
                request.randomness(),
                request.generationSeed(),
                request.avoidUsedTracks(),
                request.nicheArtistScale(),
                request.nicheSongScale(),
                request.safeSimilarityScale(),
                discoveryLimit
        );
    }

    /** Skipping used songs needs an account to remember them, so it only applies when logged in. */
    private Mono<Set<String>> loadUsedTrackKeys(Optional<SpotifySession> session, PlaylistGenerationRequest request) {
        if (!request.avoidUsedTracks() || session.isEmpty()) {
            return Mono.just(Set.of());
        }

        return playlistHistoryService.getUsedTrackKeys(session.get().spotifyUserId())
                .collect(Collectors.toSet());
    }

    private boolean isPreviouslyUsed(
            TrackCandidate track,
            Set<String> usedTrackKeys,
            PlaylistGenerationRequest request
    ) {
        if (!request.avoidUsedTracks()) {
            return false;
        }

        return usedTrackKeys.contains(trackKeyService.normalizedKey(track));
    }

    private boolean isRestricted(TrackCandidate track, PlaylistGenerationRequest request) {
        if (request.restrictedArtists() == null) {
            return false;
        }

        String artist = normalizeArtistName(track.artistName());

        return request.restrictedArtists()
                .stream()
                .map(this::normalizeArtistName)
                .anyMatch(restrictedArtist -> !restrictedArtist.isBlank() && restrictedArtist.equals(artist));
    }

    private static class ValidationState {
        private final long startedAtMs = System.currentTimeMillis();

        private final int targetSize;
        private final int maxTracksPerArtist;
        private final int initialAttempts;

        private int attemptsRemaining;
        private int currentRound = 1;
        private int lastCandidatePoolSize = 0;
        private boolean candidatePoolGrew = false;

        private int spotifyResolved = 0;
        private int spotifyMissed = 0;
        private int duplicateSpotifyUriResults = 0;
        private int duplicateCandidateSkips = 0;
        private int artistCapSkips = 0;

        private final List<WeightedTrack> selectedTracks = new ArrayList<>();
        private final Set<String> selectedKeys = new HashSet<>();
        private final Set<String> attemptedKeys = new HashSet<>();
        private final Set<String> seenSpotifyUris = new HashSet<>();
        private final Map<String, Integer> artistCounts = new HashMap<>();
        private final Map<String, Integer> pendingArtistLookups = new HashMap<>();

        private ValidationState(
                int targetSize,
                int maxTracksPerArtist,
                int attemptsRemaining
        ) {
            this.targetSize = targetSize;
            this.maxTracksPerArtist = maxTracksPerArtist;
            this.initialAttempts = attemptsRemaining;
            this.attemptsRemaining = attemptsRemaining;
        }

        private boolean isFull() {
            return selectedTracks.size() >= targetSize;
        }

        private int spotifyAttemptsMade() {
            return initialAttempts - attemptsRemaining;
        }
    }

    private record SpotifyLookup(
            WeightedTrack weightedTrack,
            String key,
            String artistKey,
            String requiredSpotifyArtistId,
            String spotifyUri
    ) {
        private SpotifyLookup withSpotifyUri(String uri) {
            return new SpotifyLookup(weightedTrack, key, artistKey, requiredSpotifyArtistId, uri);
        }
    }
}