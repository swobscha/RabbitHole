package rabbithole.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyRateLimitGuardTest {

    private static final long HOUR_MS = Duration.ofHours(1).toMillis();
    private static final long DAY_MS = Duration.ofDays(1).toMillis();

    @Test
    void requestsStopOnceTheDailyAllowanceIsUsedAndResumeWhenTheOldestHourAgesOut() {
        UsageCounterStore store = storeWith(List.of());
        SpotifyRateLimitGuard guard = new SpotifyRateLimitGuard(Mockito.mock(DatabaseClient.class), store, 3);

        assertTrue(guard.tryUseDailyAllowance());
        assertTrue(guard.tryUseDailyAllowance());
        assertEquals(0, guard.blockedUntilMs());

        // The last one allowed; from then on the page shows when requests resume.
        assertTrue(guard.tryUseDailyAllowance());
        assertFalse(guard.tryUseDailyAllowance());

        long now = System.currentTimeMillis();
        long thisHour = now - now % HOUR_MS;

        // All three were made this hour, so there's room again 24 hours after it started.
        assertEquals(thisHour + DAY_MS, guard.blockedUntilMs());
        assertEquals("daily-limit", guard.blockReason());
        assertTrue(guard.dailyLimitError().getReason().contains("today's Spotify allowance"));

        // Each request's count was saved, so a restart can't hand out a fresh allowance.
        Mockito.verify(store).save("spotify-hour:" + thisHour, thisHour, 3);
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
        SpotifyRateLimitGuard guard = new SpotifyRateLimitGuard(Mockito.mock(DatabaseClient.class), store, 10);
        guard.loadSavedUsage();

        assertTrue(guard.tryUseDailyAllowance());
        assertFalse(guard.tryUseDailyAllowance());
        assertEquals(earlier + DAY_MS, guard.blockedUntilMs());
    }

    @Test
    void aLimitOfZeroTurnsTheDailyAllowanceOff() {
        SpotifyRateLimitGuard guard = new SpotifyRateLimitGuard(Mockito.mock(DatabaseClient.class), storeWith(List.of()), 0);

        for (int i = 0; i < 50; i++) {
            assertTrue(guard.tryUseDailyAllowance());
        }

        assertEquals(0, guard.blockedUntilMs());
        assertEquals("", guard.blockReason());
    }

    private static UsageCounterStore storeWith(List<UsageCounterStore.SavedCount> saved) {
        UsageCounterStore store = Mockito.mock(UsageCounterStore.class);
        Mockito.when(store.load(Mockito.anyString())).thenReturn(saved);
        return store;
    }
}
