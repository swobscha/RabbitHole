package rabbithole.model;

import java.util.List;

public record SpotifyArtistSearchResult(
        String id,
        String name,
        String imageUrl,
        long followers,
        List<String> genres,
        String spotifyUrl
) {
}