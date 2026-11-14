package rabbithole.provider;

import reactor.core.publisher.Flux;
import rabbithole.model.PlaylistGenerationRequest;
import rabbithole.model.TrackCandidate;
import reactor.core.publisher.Mono;

public interface MusicApiProvider {

    Flux<TrackCandidate> findCandidateTracks(PlaylistGenerationRequest request);

    /** Loads and caches what a generation for this request will need, ahead of time. */
    default Mono<Void> prefetch(PlaylistGenerationRequest request) {
        return Mono.empty();
    }
}