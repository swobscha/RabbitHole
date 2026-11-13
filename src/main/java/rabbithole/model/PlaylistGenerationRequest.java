package rabbithole.model;

import java.util.List;

public record PlaylistGenerationRequest(
        String seedArtist,
        String seedSpotifyArtistId,
        String playlistName,
        int playlistSize,
        List<String> restrictedArtists,
        List<String> preferredArtists,
        List<String> preferredGenres,
        double randomness,
        String generationSeed,
        boolean avoidUsedTracks,
        double nicheArtistScale,
        double nicheSongScale,
        Double similarityScale,
        Integer discoveryLimit
) {
    public int safeDiscoveryLimit() {
        if (discoveryLimit == null || discoveryLimit <= 0) {
            return 100;
        }

        return discoveryLimit;
    }

    public double safeSimilarityScale() {
        if (similarityScale == null) {
            return 0.5;
        }

        return Math.max(0.0, Math.min(1.0, similarityScale));
    }

    public String safeSeedSpotifyArtistId() {
        if (seedSpotifyArtistId == null) {
            return "";
        }

        return seedSpotifyArtistId.trim();
    }
}