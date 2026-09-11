package spotifybot.service;

import org.springframework.stereotype.Service;
import spotifybot.model.PlaylistGenerationRequest;
import spotifybot.model.TrackCandidate;
import spotifybot.model.WeightedTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
public class TrackScoringService {

    public WeightedTrack score(TrackCandidate track, PlaylistGenerationRequest request) {
        List<String> reasons = new ArrayList<>();

        double nicheArtistScale = clamp01(request.nicheArtistScale());
        double nicheSongScale = clamp01(request.nicheSongScale());
        double songStreamDirection = sliderDirectionFromNeutral(nicheSongScale);
        double songStreamStrength = Math.abs(songStreamDirection);
        double similarityScale = request.safeSimilarityScale();
        double randomnessScale = clamp01(request.randomness());

        double score = 50.0;

        double artistSimilarity = normalizeSimilarity(track.artistSimilarity());

        /*
         * Artist rarity is artist-only.
         * Niche Artist does NOT control similarity anymore.
         */
        double artistRarity = rarityFromCounts(
                track.artistListeners(),
                track.artistPlaycount(),
                2.0,
                6.4,
                0.80,
                0.30
        );

        double artistMainstream = 1.0 - artistRarity;

        /*
         * Song rarity is track-only.
         *
         * Niche Song now works like:
         * 0.0 = prefer high-stream songs
         * 0.5 = neutral
         * 1.0 = prefer low-stream songs
         */
        double trackReach = trackReach(track);
        double songRarity = rarityFromReach(
                trackReach,
                1.40,
                5.70,
                0.05
        );

        double songMainstream = 1.0 - songRarity;

        double sourceScore = sourceScore(track);
        double sourceWeight = 1.0 - (nicheArtistScale * 0.70) - (songStreamStrength * 0.80);
        sourceWeight = Math.max(0.03, sourceWeight);

        score += sourceScore * sourceWeight;

        if (sourceScore > 0) {
            reasons.add("Source quality +" + round(sourceScore * sourceWeight));
        }

        double controlStrength = Math.max(nicheArtistScale, songStreamStrength);

        if (matchesPreferredArtist(track, request)) {
            double preferredArtistBonus = 26.0 * (1.0 - (controlStrength * 0.40));
            score += preferredArtistBonus;
            reasons.add("Preferred artist +" + round(preferredArtistBonus));
        }

        int genreMatches = preferredGenreMatches(track, request);

        if (genreMatches > 0) {
            double genreBonus = genreMatches * 18.0 * (1.0 - (controlStrength * 0.30));
            score += genreBonus;
            reasons.add("Preferred genre/tag match +" + round(genreBonus));
        }

        /*
         * Niche Artist:
         * artist listeners/playcount only.
         * Similarity is handled separately below.
         */
        if (nicheArtistScale > 0) {
            double nicheArtistPower = Math.pow(nicheArtistScale, 1.05);

            double artistRarityBonus = artistRarity * 780.0 * nicheArtistPower;
            double artistMainstreamPenalty = artistMainstream * 640.0 * nicheArtistPower;

            score += artistRarityBonus;
            score -= artistMainstreamPenalty;

            reasons.add("Niche artist low reach +" + round(artistRarityBonus));

            if (artistMainstreamPenalty > 0) {
                reasons.add("Artist mainstream penalty -" + round(artistMainstreamPenalty));
            }
        }

        /*
         * Song stream slider:
         *
         * 0.0 = high-stream song mode
         * 0.5 = neutral, no song-stream adjustment
         * 1.0 = low-stream / niche song mode
         */
        if (songStreamStrength > 0) {
            double songPower = Math.pow(songStreamStrength, 1.02);

            if (songStreamDirection > 0) {
                /*
                 * Low-stream song mode.
                 * This keeps the current Niche Song behavior at slider = 1.
                 */
                double confidence = trackReachConfidence(trackReach);
                double ultraLowReachPenalty = ultraLowTrackReachPenalty(trackReach) * songPower;

                double trackRarityBonus = songRarity * confidence * 1000.0 * songPower;
                double trackMainstreamPenalty = songMainstream * 700.0 * songPower;

                score += trackRarityBonus;
                score -= trackMainstreamPenalty;
                score -= ultraLowReachPenalty;

                reasons.add("Low-stream song reach +" + round(trackRarityBonus));
                reasons.add("Track data confidence x" + roundPercent(confidence) + "%");

                if (trackMainstreamPenalty > 0) {
                    reasons.add("High-stream song penalty -" + round(trackMainstreamPenalty));
                }

                if (ultraLowReachPenalty > 0) {
                    reasons.add("Ultra-low track data penalty -" + round(ultraLowReachPenalty));
                }
            } else {
                /*
                 * High-stream song mode.
                 * This is the inverse of low-stream mode.
                 */
                double highStreamBonus = songMainstream * 900.0 * songPower;
                double lowStreamPenalty = songRarity * 650.0 * songPower;
                double ultraLowReachPenalty = ultraLowTrackReachPenalty(trackReach) * songPower;

                score += highStreamBonus;
                score -= lowStreamPenalty;
                score -= ultraLowReachPenalty;

                reasons.add("High-stream song reach +" + round(highStreamBonus));

                if (lowStreamPenalty > 0) {
                    reasons.add("Low-stream song penalty -" + round(lowStreamPenalty));
                }

                if (ultraLowReachPenalty > 0) {
                    reasons.add("Ultra-low track data penalty -" + round(ultraLowReachPenalty));
                }
            }
        } else {
            reasons.add("Song stream preference neutral");
        }

        /*
         * Similarity to Seed Artist:
         *
         * 0.0 = prefer farther artists
         * 0.5 = neutral
         * 1.0 = prefer closer artists
         */
        double similarityDirection = (similarityScale - 0.5) * 2.0;
        double similarityStrength = Math.abs(similarityDirection);

        if (similarityStrength > 0) {
            double desiredSimilarityScore = similarityDirection > 0
                    ? artistSimilarity
                    : 1.0 - artistSimilarity;

            double undesiredSimilarityScore = 1.0 - desiredSimilarityScore;

            double nicheMultiplier = 1.0 + (nicheArtistScale * 0.45) + (songStreamStrength * 0.25);

            double similarityBonus = desiredSimilarityScore * 420.0 * similarityStrength * nicheMultiplier;
            double similarityPenalty = undesiredSimilarityScore * 260.0 * similarityStrength * nicheMultiplier;

            score += similarityBonus;
            score -= similarityPenalty;

            if (similarityDirection > 0) {
                reasons.add("Closer-to-seed similarity +" + round(similarityBonus));
                reasons.add("Far-artist similarity penalty -" + round(similarityPenalty));
            } else {
                reasons.add("Farther-from-seed similarity +" + round(similarityBonus));
                reasons.add("Too-close similarity penalty -" + round(similarityPenalty));
            }
        } else {
            reasons.add("Similarity neutral");
        }

        /*
         * Seed artist penalty intentionally removed.
         */

        if (randomnessScale > 0) {
            double randomnessWindow = 80.0 * randomnessScale * (1.0 - (controlStrength * 0.45));
            double randomAdjustment = deterministicRandomAdjustment(track, request, randomnessWindow);

            score += randomAdjustment;

            if (randomAdjustment >= 0) {
                reasons.add("Random boost +" + round(randomAdjustment));
            } else {
                reasons.add("Random penalty -" + round(Math.abs(randomAdjustment)));
            }
        }

        return new WeightedTrack(
                track,
                score,
                reasons
        );
    }

    private double sourceScore(TrackCandidate track) {
        double score = 0.0;

        String sourceApi = track.sourceApi() == null ? "" : track.sourceApi().toLowerCase();

        if (sourceApi.contains("last")) {
            score += 16.0;
        }

        double popularity = safeDouble(track.popularity());

        if (popularity > 0) {
            score += Math.min(18.0, popularity * 0.18);
        }

        return score;
    }

    private boolean matchesPreferredArtist(
            TrackCandidate track,
            PlaylistGenerationRequest request
    ) {
        if (request.preferredArtists() == null || request.preferredArtists().isEmpty()) {
            return false;
        }

        String artistName = normalize(track.artistName());

        return request.preferredArtists()
                .stream()
                .filter(artist -> artist != null && !artist.isBlank())
                .map(this::normalize)
                .anyMatch(preferredArtist ->
                        artistName.equals(preferredArtist)
                                || artistName.contains(preferredArtist)
                                || preferredArtist.contains(artistName)
                );
    }

    private int preferredGenreMatches(
            TrackCandidate track,
            PlaylistGenerationRequest request
    ) {
        if (request.preferredGenres() == null || request.preferredGenres().isEmpty()) {
            return 0;
        }

        if (track.genres() == null || track.genres().isEmpty()) {
            return 0;
        }

        int matches = 0;

        for (String preferredGenre : request.preferredGenres()) {
            String normalizedPreferredGenre = normalize(preferredGenre);

            if (normalizedPreferredGenre.isBlank()) {
                continue;
            }

            for (String trackGenre : track.genres()) {
                String normalizedTrackGenre = normalize(trackGenre);

                if (normalizedTrackGenre.equals(normalizedPreferredGenre)
                        || normalizedTrackGenre.contains(normalizedPreferredGenre)
                        || normalizedPreferredGenre.contains(normalizedTrackGenre)) {
                    matches++;
                    break;
                }
            }
        }

        return matches;
    }

    private double trackReach(TrackCandidate track) {
        double listeners = safeDouble(track.trackListeners());
        double playcount = safeDouble(track.trackPlaycount());

        return Math.max(listeners, playcount);
    }

    private double trackReachConfidence(double trackReach) {
        if (trackReach <= 0) {
            return 0.10;
        }

        if (trackReach <= 2) {
            return 0.35;
        }

        if (trackReach <= 9) {
            return 0.55;
        }

        if (trackReach <= 24) {
            return 0.72;
        }

        if (trackReach <= 49) {
            return 0.88;
        }

        return 1.00;
    }

    private double ultraLowTrackReachPenalty(double trackReach) {
        if (trackReach <= 0) {
            return 300.0;
        }

        if (trackReach <= 2) {
            return 180.0;
        }

        if (trackReach <= 9) {
            return 80.0;
        }

        return 0.0;
    }

    private double rarityFromCounts(
            Object listenersValue,
            Object playcountValue,
            double lowLog,
            double highLog,
            double playcountWeight,
            double missingFallback
    ) {
        double listeners = safeDouble(listenersValue);
        double playcount = safeDouble(playcountValue);

        double combinedReach = Math.max(
                listeners,
                playcount * playcountWeight
        );

        return rarityFromReach(
                combinedReach,
                lowLog,
                highLog,
                missingFallback
        );
    }

    private double rarityFromReach(
            double reach,
            double lowLog,
            double highLog,
            double missingFallback
    ) {
        if (reach <= 0) {
            return missingFallback;
        }

        double logReach = Math.log10(reach + 1.0);
        double mainstreamScore = clamp01((logReach - lowLog) / (highLog - lowLog));

        return 1.0 - mainstreamScore;
    }

    private double normalizeSimilarity(Object similarityValue) {
        double similarity = safeDouble(similarityValue);

        if (similarity <= 0) {
            return 0.50;
        }

        if (similarity > 1.0) {
            similarity = similarity / 100.0;
        }

        return clamp01(similarity);
    }

    private double deterministicRandomAdjustment(
            TrackCandidate track,
            PlaylistGenerationRequest request,
            double window
    ) {
        String seedText = String.valueOf(request.generationSeed())
                + "|"
                + normalize(track.artistName())
                + "|"
                + normalize(track.name());

        Random random = new Random(seedText.hashCode());
        return (random.nextDouble() * 2.0 - 1.0) * window;
    }

    private double safeDouble(Object value) {
        if (value == null) {
            return 0.0;
        }

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException error) {
            return 0.0;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value.toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private double sliderDirectionFromNeutral(double value) {
        return (clamp01(value) - 0.5) * 2.0;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private int round(double value) {
        return (int) Math.round(value);
    }

    private int roundPercent(double value) {
        return (int) Math.round(value * 100.0);
    }
}