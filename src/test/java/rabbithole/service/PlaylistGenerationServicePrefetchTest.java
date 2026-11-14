package rabbithole.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import rabbithole.model.PlaylistGenerationRequest;
import rabbithole.provider.MusicApiProvider;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaylistGenerationServicePrefetchTest {

    @Test
    void atMostTwoPrefetchesRunAtOnceAndSkippedArtistsCanBePrefetchedLater() {
        List<Sinks.Empty<Void>> running = new CopyOnWriteArrayList<>();
        MusicApiProvider provider = Mockito.mock(MusicApiProvider.class);

        Mockito.when(provider.prefetch(Mockito.any(PlaylistGenerationRequest.class))).thenAnswer(ignored -> {
            Sinks.Empty<Void> prefetch = Sinks.empty();
            running.add(prefetch);
            return prefetch.asMono();
        });

        // Prefetch only uses the providers.
        PlaylistGenerationService service = new PlaylistGenerationService(
                List.of(provider), null, null, null, null, null, null
        );

        service.prefetch("First");
        service.prefetch("Second");
        service.prefetch("Third");

        assertEquals(2, running.size());

        running.get(0).tryEmitEmpty();
        service.prefetch("Third");

        // The earlier skip didn't count as done, so the artist is prefetched once there's room.
        assertEquals(3, running.size());
    }

    @Test
    void aFailedPrefetchFreesItsPlace() {
        MusicApiProvider provider = Mockito.mock(MusicApiProvider.class);
        Mockito.when(provider.prefetch(Mockito.any(PlaylistGenerationRequest.class)))
                .thenReturn(Mono.error(new IllegalStateException("Last.fm unavailable")));

        PlaylistGenerationService service = new PlaylistGenerationService(
                List.of(provider), null, null, null, null, null, null
        );

        service.prefetch("One");
        service.prefetch("Two");
        service.prefetch("Three");

        Mockito.verify(provider, Mockito.times(3)).prefetch(Mockito.any(PlaylistGenerationRequest.class));
    }
}
