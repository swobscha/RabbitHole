package spotifybot.model;

import java.util.List;

public record PlaylistReplaceTrackRequest(
        PlaylistGenerationRequest generationRequest,
        List<WeightedTrack> currentTracks,
        WeightedTrack trackToReplace
) {
    public List<WeightedTrack> safeCurrentTracks() {
        return currentTracks == null ? List.of() : currentTracks;
    }
}
