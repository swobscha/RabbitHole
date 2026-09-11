package spotifybot.model;

import java.util.List;

public record SpotifyTrackSearchResponse(
        SpotifyTrackPage tracks
) {
    public record SpotifyTrackPage(
            List<SpotifyTrackItem> items
    ) {
    }

    public record SpotifyTrackItem(
            String id,
            String name,
            String uri,
            Integer popularity,
            List<SpotifyArtist> artists,
            SpotifyAlbum album
    ) {
    }

    public record SpotifyArtist(
            String id,
            String name
    ) {
    }

    public record SpotifyAlbum(
            String id,
            String name
    ) {
    }
}