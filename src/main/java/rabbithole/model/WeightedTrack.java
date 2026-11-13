package rabbithole.model;

import java.util.List;

public record WeightedTrack(
        TrackCandidate track,
        double score,
        List<String> reasons
) {
}