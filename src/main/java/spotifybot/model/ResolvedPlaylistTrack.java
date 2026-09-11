package spotifybot.model;

public record ResolvedPlaylistTrack(
        WeightedTrack weightedTrack,
        String spotifyUri
) {
}