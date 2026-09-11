package spotifybot.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spotifybot.model.PlaylistGenerationRequest;
import spotifybot.model.PlaylistGenerationResult;
import spotifybot.model.PlaylistReplaceTrackRequest;
import spotifybot.model.TrackCandidate;
import spotifybot.model.WeightedTrack;
import spotifybot.provider.MusicApiProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PlaylistGenerationService {

    private static final int DISCOVERY_BATCH_SIZE = 100;
    private static final int MAX_DISCOVERY_ROUNDS = 20;
    private static final int MAX_SPOTIFY_LOOKUP_ATTEMPTS = 60;

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

    public Mono<PlaylistGenerationResult> generatePlaylist(PlaylistGenerationRequest request) {
        validateGenerationRequest(request);

        int playlistSize = request.playlistSize();

        System.out.println(
                "GENERATION START"
                        + " | playlistName=" + request.playlistName()
                        + " | seedArtist=" + request.seedArtist()
                        + " | requestedSize=" + playlistSize
                        + " | maxSpotifyLookupAttempts=" + MAX_SPOTIFY_LOOKUP_ATTEMPTS
                        + " | popularityScale=" + request.nicheSongScale()
                        + " | similarityScale=" + request.safeSimilarityScale()
                        + " | avoidUsedTracks=" + request.avoidUsedTracks()
        );

        return spotifyAuthService.getValidAccessToken()
                .flatMap(accessToken ->
                        loadUsedTrackKeys(request)
                                .flatMap(usedTrackKeys -> {
                                    ValidationState state = new ValidationState(
                                            playlistSize,
                                            maxTracksPerArtist(request, playlistSize),
                                            MAX_SPOTIFY_LOOKUP_ATTEMPTS
                                    );

                                    return generateWithExpansion(
                                            request,
                                            usedTrackKeys,
                                            playlistSize,
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

    public Mono<WeightedTrack> replaceTrack(PlaylistReplaceTrackRequest request) {
        validateReplaceTrackRequest(request);

        PlaylistGenerationRequest original = request.generationRequest();

        Set<String> blockedTrackKeys = request.safeCurrentTracks()
                .stream()
                .map(this::trackKey)
                .filter(key -> !key.isBlank())
                .collect(Collectors.toSet());

        Set<String> blockedSpotifyUris = request.safeCurrentTracks()
                .stream()
                .map(this::spotifyUri)
                .filter(uri -> !uri.isBlank())
                .collect(Collectors.toSet());

        PlaylistGenerationRequest replacementRequest = new PlaylistGenerationRequest(
                original.seedArtist(),
                original.seedSpotifyArtistId(),
                original.playlistName(),
                request.safeReplacementSearchSize(),
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

        System.out.println(
                "REPLACE TRACK START"
                        + " | seedArtist=" + original.seedArtist()
                        + " | currentTracks=" + request.safeCurrentTracks().size()
                        + " | replacementSearchSize=" + request.safeReplacementSearchSize()
        );

        return generatePlaylist(replacementRequest)
                .map(result -> result.tracks()
                        .stream()
                        .filter(candidate -> {
                            String key = trackKey(candidate);
                            String uri = spotifyUri(candidate);

                            return !blockedTrackKeys.contains(key)
                                    && !blockedSpotifyUris.contains(uri);
                        })
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Could not find a fresh replacement track. Try changing advanced settings or generating again."
                        ))
                )
                .doOnNext(replacement -> System.out.println(
                        "REPLACE TRACK FOUND"
                                + " | artist=" + replacement.track().artistName()
                                + " | track=" + replacement.track().name()
                                + " | spotifyUri=" + replacement.track().spotifyUri()
                ));
    }

    private Mono<PlaylistGenerationResult> generateWithExpansion(
            PlaylistGenerationRequest originalRequest,
            Set<String> usedTrackKeys,
            int playlistSize,
            String accessToken,
            int round,
            ValidationState state
    ) {
        int discoveryLimit = DISCOVERY_BATCH_SIZE * round;

        PlaylistGenerationRequest expandedRequest = withDiscoveryLimit(
                originalRequest,
                discoveryLimit
        );

        return generateSingleRound(expandedRequest, usedTrackKeys, accessToken, round, state)
                .flatMap(result -> {
                    boolean playlistIsFull = result.tracks().size() >= playlistSize;
                    boolean reachedMaxRounds = round >= MAX_DISCOVERY_ROUNDS;
                    boolean attemptsExhausted = state.attemptsRemaining <= 0;

                    if (playlistIsFull || reachedMaxRounds || attemptsExhausted) {
                        String stopReason;

                        if (playlistIsFull) {
                            stopReason = "playlist full";
                        } else if (attemptsExhausted) {
                            stopReason = "Spotify lookup attempts exhausted";
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

                        return Mono.just(result);
                    }

                    return generateWithExpansion(
                            originalRequest,
                            usedTrackKeys,
                            playlistSize,
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

                    System.out.println(
                            "ROUND " + round
                                    + " | Last.fm scored candidates=" + sortedTracks.size()
                                    + " | selectedSoFar=" + state.selectedTracks.size()
                                    + " | SpotifyAttemptsMade=" + state.spotifyAttemptsMade()
                                    + " | SpotifyAttemptsLeft=" + state.attemptsRemaining
                                    + " | discoveryLimit=" + request.safeDiscoveryLimit()
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

        System.out.println(
                "ROUND " + round
                        + " | unique Spotify candidates after cleanup="
                        + uniqueSortedTracks.size()
        );

        return Flux.fromIterable(uniqueSortedTracks)
                .concatMap(weightedTrack -> {
                    if (state.isFull() || state.attemptsRemaining <= 0) {
                        return Mono.empty();
                    }

                    String key = trackKey(weightedTrack);

                    if (state.attemptedKeys.contains(key)) {
                        state.duplicateCandidateSkips++;
                        return Mono.empty();
                    }

                    if (state.selectedKeys.contains(key)) {
                        state.duplicateCandidateSkips++;
                        return Mono.empty();
                    }

                    String artistKey = normalizeArtistName(weightedTrack.track().artistName());
                    int currentArtistCount = state.artistCounts.getOrDefault(artistKey, 0);

                    if (currentArtistCount >= state.maxTracksPerArtist) {
                        state.artistCapSkips++;
                        return Mono.empty();
                    }

                    String requiredSpotifyArtistId = requiredSpotifyArtistIdForCandidate(
                            request,
                            weightedTrack.track()
                    );

                    state.attemptedKeys.add(key);
                    state.attemptsRemaining--;

                    return spotifyTrackSearchService.findSpotifyUri(
                                    accessToken,
                                    weightedTrack.track(),
                                    requiredSpotifyArtistId
                            )
                            .doOnNext(uri -> {
                                if (state.seenSpotifyUris.contains(uri)) {
                                    state.duplicateSpotifyUriResults++;
                                    return;
                                }

                                WeightedTrack resolvedTrack = withSpotifyUri(weightedTrack, uri);

                                state.selectedTracks.add(resolvedTrack);
                                state.selectedKeys.add(key);
                                state.seenSpotifyUris.add(uri);
                                state.artistCounts.put(artistKey, currentArtistCount + 1);
                                state.spotifyResolved++;
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                state.spotifyMissed++;
                                return Mono.empty();
                            }))
                            .then();
                })
                .then(Mono.fromSupplier(() -> {
                    System.out.println(
                            "ROUND " + round
                                    + " COMPLETE"
                                    + " | Spotify-valid selected=" + state.selectedTracks.size()
                                    + " | attemptsMade=" + state.spotifyAttemptsMade()
                                    + " | attemptsLeft=" + state.attemptsRemaining
                                    + " | resolved=" + state.spotifyResolved
                                    + " | misses=" + state.spotifyMissed
                                    + " | duplicateSpotifyUris=" + state.duplicateSpotifyUriResults
                                    + " | artistCapSkips=" + state.artistCapSkips
                    );

                    rankingDebugService.writeSelectedRankingDebug(
                            request,
                            round,
                            state.selectedTracks
                    );

                    return List.copyOf(state.selectedTracks);
                }));
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

        if (request.safeReplacementSearchSize() < MIN_PLAYLIST_SIZE
                || request.safeReplacementSearchSize() > MAX_PLAYLIST_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Replacement search size must be between 1 and 50."
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

        System.out.println(
                "GENERATION SUMMARY"
                        + " | stopReason=" + stopReason
                        + " | playlistName=" + request.playlistName()
                        + " | seedArtist=" + request.seedArtist()
                        + " | requestedSize=" + state.targetSize
                        + " | returnedTracks=" + result.tracks().size()
                        + " | rounds=" + finalRound
                        + " | maxSpotifyLookupAttempts=" + state.initialAttempts
                        + " | attemptsMade=" + state.spotifyAttemptsMade()
                        + " | attemptsLeft=" + state.attemptsRemaining
                        + " | resolved=" + state.spotifyResolved
                        + " | misses=" + state.spotifyMissed
                        + " | duplicateSpotifyUris=" + state.duplicateSpotifyUriResults
                        + " | duplicateCandidateSkips=" + state.duplicateCandidateSkips
                        + " | artistCapSkips=" + state.artistCapSkips
                        + " | elapsedMs=" + elapsedMs
        );
    }

    private void printGenerationErrorSummary(
            PlaylistGenerationRequest request,
            int round,
            ValidationState state,
            Throwable error
    ) {
        long elapsedMs = System.currentTimeMillis() - state.startedAtMs;

        System.out.println(
                "GENERATION ERROR SUMMARY"
                        + " | playlistName=" + request.playlistName()
                        + " | seedArtist=" + request.seedArtist()
                        + " | round=" + round
                        + " | maxSpotifyLookupAttempts=" + state.initialAttempts
                        + " | attemptsMade=" + state.spotifyAttemptsMade()
                        + " | attemptsLeft=" + state.attemptsRemaining
                        + " | resolved=" + state.spotifyResolved
                        + " | misses=" + state.spotifyMissed
                        + " | duplicateSpotifyUris=" + state.duplicateSpotifyUriResults
                        + " | duplicateCandidateSkips=" + state.duplicateCandidateSkips
                        + " | artistCapSkips=" + state.artistCapSkips
                        + " | elapsedMs=" + elapsedMs
                        + " | error=" + error.getMessage()
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

        return normalizeArtistName(weightedTrack.track().artistName())
                + "|"
                + normalizeTrackTitle(weightedTrack.track().name());
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
        if (artistName == null) {
            return "";
        }

        return artistName.toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private String normalizeTrackTitle(String value) {
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
                .replaceAll("\\([^)]*music video[^)]*\\)", "")
                .replaceAll("\\[[^]]*music video[^]]*]", "")
                .replaceAll("\\([^)]*mv[^)]*\\)", "")
                .replaceAll("\\[[^]]*mv[^]]*]", "")
                .replaceAll("\\([^)]*live[^)]*\\)", "")
                .replaceAll("\\[[^]]*live[^]]*]", "")
                .replaceAll("\\s+live\\s+complexcon\\s+\\d{4}.*$", "")
                .replaceAll("\\s+complexcon\\s+\\d{4}.*$", "")
                .replaceAll("^\\d+\\.\\s*", "")
                .replaceAll("\\s+mv$", "")
                .replaceAll("\\s+music\\s+video$", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
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

    private Mono<Set<String>> loadUsedTrackKeys(PlaylistGenerationRequest request) {
        if (!request.avoidUsedTracks()) {
            return Mono.just(Set.of());
        }

        return playlistHistoryService.getUsedTrackKeys()
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

        return request.restrictedArtists()
                .stream()
                .anyMatch(restrictedArtist ->
                        restrictedArtist.equalsIgnoreCase(track.artistName())
                );
    }

    private static class ValidationState {
        private final long startedAtMs = System.currentTimeMillis();

        private final int targetSize;
        private final int maxTracksPerArtist;
        private final int initialAttempts;

        private int attemptsRemaining;
        private int currentRound = 1;

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
}