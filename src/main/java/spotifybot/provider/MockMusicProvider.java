package spotifybot.provider;

import spotifybot.model.PlaylistGenerationRequest;
import spotifybot.model.TrackCandidate;
import reactor.core.publisher.Flux;

import java.util.Set;


public class MockMusicProvider implements MusicApiProvider {

    @Override
    public Flux<TrackCandidate> findCandidateTracks(PlaylistGenerationRequest request) {
        return Flux.just(
                new TrackCandidate(
                        "mock-1",
                        "Neon Circuit",
                        "Lunar Static",
                        "artist-1",
                        "Electric Nights",
                        82,
                        Set.of("electronic", "dance", "synth"),
                        "mock",
                        null
                ),
                new TrackCandidate(
                        "mock-2",
                        "Chrome Heartbeat",
                        "Neon Atlas",
                        "artist-2",
                        "City Signals",
                        76,
                        Set.of("electronic", "pop", "indie"),
                        "mock",
                        null
                ),
                new TrackCandidate(
                        "mock-3",
                        "Late Night Algorithm",
                        "Data Bloom",
                        "artist-3",
                        "Machine Dreams",
                        67,
                        Set.of("electronic", "ambient", "experimental"),
                        "mock",
                        null
                ),
                new TrackCandidate(
                        "mock-4",
                        "Velvet Satellite",
                        "Solar Echo",
                        "artist-4",
                        "Orbit Lounge",
                        71,
                        Set.of("funk", "indie", "groove"),
                        "mock",
        null
                ),
                new TrackCandidate(
                        "mock-5",
                        "Robot Summer",
                        "Lunar Static",
                        "artist-1",
                        "Electric Nights",
                        88,
                        Set.of("electronic", "funk", "dance"),
                        "mock",
        null
                ),
                new TrackCandidate(
                        "mock-6",
                        "Glass Horizon",
                        "Midnight Relay",
                        "artist-5",
                        "Signals",
                        59,
                        Set.of("ambient", "synth", "chill"),
                        "mock",
        null
                ),
                new TrackCandidate(
                        "mock-7",
                        "Binary Sunset",
                        "Data Bloom",
                        "artist-3",
                        "Machine Dreams",
                        73,
                        Set.of("electronic", "synth", "indie"),
                        "mock",
        null
                ),
                new TrackCandidate(
                        "mock-8",
                        "Golden Static",
                        "Neon Atlas",
                        "artist-2",
                        "City Signals",
                        91,
                        Set.of("pop", "dance", "electronic"),
                        "mock",
        null
                ),
                new TrackCandidate(
                        "mock-9",
                        "Analog Rain",
                        "Solar Echo",
                        "artist-4",
                        "Orbit Lounge",
                        64,
                        Set.of("funk", "soul", "groove"),
                        "mock",
        null
                ),
                new TrackCandidate(
                        "mock-10",
                        "Dream Cache",
                        "Midnight Relay",
                        "artist-5",
                        "Signals",
                        69,
                        Set.of("chill", "ambient", "electronic"),
                        "mock",
        null
                )
        );
    }
}