package spotifybot.model;

public record CreatedPlaylistHistoryItem(
        String id,
        String playlistName,
        String seedArtist,
        int trackCount,
        String spotifyPlaylistUrl,
        String createdAt
) {
}