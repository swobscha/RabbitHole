package rabbithole.provider;

import reactor.core.publisher.Flux;
import rabbithole.model.PlaylistGenerationRequest;
import rabbithole.model.TrackCandidate;
import rabbithole.model.WeightedTrack;
import reactor.core.publisher.Mono;

import java.util.List;

public interface MusicApiProvider {

    Flux<TrackCandidate> findCandidateTracks(PlaylistGenerationRequest request);

    /** Fills in details that are only worth fetching for the tracks that made the playlist. */
    default Mono<List<WeightedTrack>> addArtistDetails(List<WeightedTrack> tracks) {
        return Mono.just(tracks);
    }

    /** Loads and caches what a generation for this request will need, ahead of time. */
    default Mono<Void> prefetch(PlaylistGenerationRequest request) {
        return Mono.empty();
    }
}