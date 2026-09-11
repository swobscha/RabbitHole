package spotifybot.model;

public record CreatedPlaylistResponse(
        String playlistName,
        int trackCount,
        String status,
        String spotifyPlaylistUrl
) {
}