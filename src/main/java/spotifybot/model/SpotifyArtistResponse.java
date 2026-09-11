package spotifybot.model;

import java.util.List;

public record SpotifyArtistResponse(
        String id,
        String name,
        List<String> genres
) {
}