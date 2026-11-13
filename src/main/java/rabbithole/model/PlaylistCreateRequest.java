package rabbithole.model;

import java.util.List;

public record PlaylistCreateRequest(
        String playlistName,
        String seedArtist,
        List<WeightedTrack> tracks,
        Integer targetTrackCount
) {
    public List<WeightedTrack> safeTracks() {
        return tracks == null ? List.of() : tracks;
    }

    public int safeTargetTrackCount() {
        if (targetTrackCount != null && targetTrackCount > 0) {
            return targetTrackCount;
        }

        return safeTracks().size();
    }
}
