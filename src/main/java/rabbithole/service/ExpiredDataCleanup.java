package rabbithole.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Saved Spotify answers stop being used after a while but would otherwise stay in the database for
 * good. Anyone searching made-up names could then slowly fill it, so expired rows are deleted once a
 * day, starting a minute after startup. Logins idle for 30 days are deleted at the same time.
 */
@Service
public class ExpiredDataCleanup implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExpiredDataCleanup.class);

    private static final Duration FIRST_RUN_DELAY = Duration.ofMinutes(1);
    private static final Duration INTERVAL = Duration.ofDays(1);

    private final SpotifyArtistSearchStore artistSearchStore;
    private final SpotifyTrackMatchStore trackMatchStore;
    private final SpotifySessionStore sessionStore;

    private Disposable schedule;

    public ExpiredDataCleanup(
            SpotifyArtistSearchStore artistSearchStore,
            SpotifyTrackMatchStore trackMatchStore,
            SpotifySessionStore sessionStore
    ) {
        this.artistSearchStore = artistSearchStore;
        this.trackMatchStore = trackMatchStore;
        this.sessionStore = sessionStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        schedule = Flux.interval(FIRST_RUN_DELAY, INTERVAL)
                .concatMap(ignored -> runOnce())
                .subscribe();
    }

    /** Deletes expired rows once. Never fails; a failed run is retried the next day. */
    public Mono<Void> runOnce() {
        return Mono.zip(artistSearchStore.deleteExpired(), trackMatchStore.deleteExpiredMisses(), sessionStore.deleteIdle())
                .doOnNext(deleted -> {
                    if (deleted.getT1() + deleted.getT2() + deleted.getT3() > 0) {
                        log.info(
                                "Deleted expired saved data | artistSearches={} | songMisses={} | idleLogins={}",
                                deleted.getT1(),
                                deleted.getT2(),
                                deleted.getT3()
                        );
                    }
                })
                .then()
                .onErrorResume(error -> {
                    log.warn("Could not delete expired saved data; trying again tomorrow.", error);
                    return Mono.empty();
                });
    }

    @Override
    public void destroy() {
        if (schedule != null) {
            schedule.dispose();
        }
    }
}
