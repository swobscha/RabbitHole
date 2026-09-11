package spotifybot.model;

import java.util.List;

public record PlaylistCreateRequest(
        String playlistName,
        String seedArtist,
        List<WeightedTrack> tracks,
        List<WeightedTrack> reserveTracks,
        Integer targetTrackCount
) {
    public PlaylistCreateRequest(
            String playlistName,
            String seedArtist,
            List<WeightedTrack> tracks
    ) {
        this(
                playlistName,
                seedArtist,
                tracks,
                List.of(),
                tracks == null ? 0 : tracks.size()
        );
    }

    public List<WeightedTrack> safeTracks() {
        return tracks == null ? List.of() : tracks;
    }

    public List<WeightedTrack> safeReserveTracks() {
        return reserveTracks == null ? List.of() : reserveTracks;
    }

    public int safeTargetTrackCount() {
        if (targetTrackCount != null && targetTrackCount > 0) {
            return targetTrackCount;
        }

        return safeTracks().size();
    }
}