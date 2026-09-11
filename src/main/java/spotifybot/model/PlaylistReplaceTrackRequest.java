package spotifybot.model;

import java.util.List;

public record PlaylistReplaceTrackRequest(
        PlaylistGenerationRequest generationRequest,
        List<WeightedTrack> currentTracks,
        WeightedTrack trackToReplace,
        Integer replacementSearchSize
) {
    public List<WeightedTrack> safeCurrentTracks() {
        return currentTracks == null ? List.of() : currentTracks;
    }

    public int safeReplacementSearchSize() {
        if (replacementSearchSize == null || replacementSearchSize <= 0) {
            return Math.max(15, safeCurrentTracks().size() + 10);
        }

        return Math.max(1, Math.min(50, replacementSearchSize));
    }
}