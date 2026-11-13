package rabbithole.provider;

import reactor.core.publisher.Flux;
import rabbithole.model.PlaylistGenerationRequest;
import rabbithole.model.TrackCandidate;

public interface MusicApiProvider {

    Flux<TrackCandidate> findCandidateTracks(PlaylistGenerationRequest request);
}