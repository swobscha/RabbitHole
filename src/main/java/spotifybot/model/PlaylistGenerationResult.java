package spotifybot.model;

import java.util.List;

public record PlaylistGenerationResult(
        String playlistName,
        String seedArtist,
        List<WeightedTrack> tracks
) {
}