package rabbithole.model;

public record ResolvedPlaylistTrack(
        WeightedTrack weightedTrack,
        String spotifyUri
) {
}