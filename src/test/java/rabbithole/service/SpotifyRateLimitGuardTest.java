package rabbithole.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rabbithole.service.SpotifyRateLimitGuard.Allowance.ALLOWED;
import static rabbithole.service.SpotifyRateLimitGuard.Allowance.DAILY_LIMIT;
import static rabbithole.service.SpotifyRateLimitGuard.Allowance.VISITOR_LIMIT;

class SpotifyRateLimitGuardTest {

    private static final long HOUR_MS = Duration.ofHours(1).toMillis();
    private static final long DAY_MS = Duration.ofDays(1).toMillis();
    // A connected account, which can use the whole allowance.
    private static final String OWNER = "user:owner";

    @Test
    void requestsStopOnceTheDailyAllowanceIsUsedAndResumeWhenTheOldestHourAgesOut() {
        UsageCounterStore store = storeWith(List.of());
        SpotifyRateLimitGuard guard = guard(store, 3);

        assertEquals(ALLOWED, guard.tryUseDailyAllowance(OWNER));
        assertEquals(ALLOWED, guard.tryUseDailyAllowance(OWNER));
        assertEquals(0, guard.blockedUntilMs(true));

        // The last one allowed; from then on the page shows when requests resume.
        assertEquals(ALLOWED, guard.tryUseDailyAllowance(OWNER));
        assertEquals(DAILY_LIMIT, guard.tryUseDailyAllowance(OWNER));

        long now = System.currentTimeMillis();
        long thisHour = now - now % HOUR_MS;

        // All three were made this hour, so there's room again 24 hours after it started.
        assertEquals(thisHour + DAY_MS, guard.blockedUntilMs(true));
        assertEquals("daily-limit", guard.blockReason(true));
        assertTrue(guard.dailyLimitError(true).getReason().contains("today's Spotify allowance"));

        // Each request's count was saved, so a restart can't hand out a fresh allowance.
        Mockito.verify(store).save("spotify-hour:" + thisHour, thisHour, 3);
    }

    @Test
    void oneVisitorCanOnlyUseTheirShareOfTheDay() {
        // 15% of 100 is 15 requests per logged-out visitor.
        SpotifyRateLimitGuard guard = guard(storeWith(List.of()), 100);

        for (int i = 0; i < 15; i++) {
            assertEquals(ALLOWED, guard.tryUseDailyAllowance("ip:10.0.0.1"));
        }

        assertEquals(VISITOR_LIMIT, guard.tryUseDailyAllowance("ip:10.0.0.1"));
        assertTrue(guard.visitorLimitError("ip:10.0.0.1").getReason().contains("your share"));

        // Everyone else is unaffected.
        assertEquals(ALLOWED, guard.tryUseDailyAllowance("ip:10.0.0.2"));
        assertEquals(0, guard.blockedUntilMs(false));
    }

    @Test
    void connectedAccountsKeepAReservedPartWhenEveryoneElsesIsUsed() {
        // 80% of 10 is 8 for logged-out visitors; 15% of 10 rounds down, so each visitor gets 1.
        SpotifyRateLimitGuard guard = guard(storeWith(List.of()), 10);

        for (int i = 0; i < 8; i++) {
            assertEquals(ALLOWED, guard.tryUseDailyAllowance("ip:10.0.1." + i));
        }

        assertEquals(DAILY_LIMIT, guard.tryUseDailyAllowance("ip:10.0.2.1"));
        assertEquals("daily-limit", guard.blockReason(false));
        assertTrue(guard.blockedUntilMs(false) > 0);

        // Finishing a login (no known visitor yet) can't dip into the reserved part either.
        assertEquals(DAILY_LIMIT, guard.tryUseDailyAllowance(null));

        // An approved, connected account still gets through.
        assertEquals(0, guard.blockedUntilMs(true));
        assertEquals(ALLOWED, guard.tryUseDailyAllowance("user:owner"));
        assertEquals(ALLOWED, guard.tryUseDailyAllowance("user:owner"));
        assertEquals(DAILY_LIMIT, guard.tryUseDailyAllowance("user:owner"));
    }

    @Test
    void theGuardReadsWhoTheRequestIsForFromTheRequestContext() {
        SpotifyRateLimitGuard guard = guard(storeWith(List.of()), 10);
        ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("https://api.spotify.test/v1/search")).build();
        ClientResponse ok = ClientResponse.create(HttpStatus.OK).build();

        // Each visitor gets 1 of 10.
        guard.filter()
                .filter(request, ignored -> Mono.just(ok))
                .contextWrite(context -> context.put(SpotifyRateLimitGuard.VISITOR_CONTEXT_KEY, "ip:10.0.0.5"))
                .block();

        ResponseStatusException refused = assertThrows(ResponseStatusException.class, () -> guard.filter()
                .filter(request, ignored -> Mono.just(ok))
                .contextWrite(context -> context.put(SpotifyRateLimitGuard.VISITOR_CONTEXT_KEY, "ip:10.0.0.5"))
                .block());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, refused.getStatusCode());
        assertTrue(refused.getReason().contains("your share"));
    }

    @Test
    void requestsCountedBeforeARestartStillUseTheAllowance() {
        long now = System.currentTimeMillis();
        long earlier = now - now % HOUR_MS - 5 * HOUR_MS;
        long tooOld = now - now % HOUR_MS - 30 * HOUR_MS;

        UsageCounterStore store = storeWith(List.of(
                new UsageCounterStore.SavedCount("spotify-hour:" + earlier, earlier, 9),
                // Older than a day, so it no longer counts.
                new UsageCounterStore.SavedCount("spotify-hour:" + tooOld, tooOld, 500)
        ));
        SpotifyRateLimitGuard guard = guard(store, 10);
        guard.loadSavedUsage();

        assertEquals(ALLOWED, guard.tryUseDailyAllowance(OWNER));
        assertEquals(DAILY_LIMIT, guard.tryUseDailyAllowance(OWNER));
        assertEquals(earlier + DAY_MS, guard.blockedUntilMs(true));
    }

    @Test
    void aLimitOfZeroTurnsTheDailyAllowanceOff() {
        SpotifyRateLimitGuard guard = guard(storeWith(List.of()), 0);

        for (int i = 0; i < 50; i++) {
            assertEquals(ALLOWED, guard.tryUseDailyAllowance("ip:10.0.0.1"));
        }

        assertEquals(0, guard.blockedUntilMs(false));
        assertEquals("", guard.blockReason(false));
    }

    private static SpotifyRateLimitGuard guard(UsageCounterStore store, int dailyLimit) {
        return new SpotifyRateLimitGuard(Mockito.mock(DatabaseClient.class), store, dailyLimit);
    }

    private static UsageCounterStore storeWith(List<UsageCounterStore.SavedCount> saved) {
        UsageCounterStore store = Mockito.mock(UsageCounterStore.class);
        Mockito.when(store.load(Mockito.anyString())).thenReturn(saved);
        return store;
    }
}
