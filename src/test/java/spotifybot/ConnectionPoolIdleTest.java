package spotifybot;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses the real pool settings from application.properties, with only the idle time shortened,
 * to check that idle connections are actually closed and nothing keeps one open.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.r2dbc.url=r2dbc:h2:mem:///poolidle;DB_CLOSE_DELAY=-1",
        "spring.r2dbc.pool.max-idle-time=1s"
})
class ConnectionPoolIdleTest {

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private DatabaseClient databaseClient;

    @Test
    void idleConnectionsAreClosed() throws InterruptedException {
        ConnectionPool pool = (ConnectionPool) connectionFactory;

        databaseClient.sql("SELECT 1").fetch().rowsUpdated().block();

        assertTrue(pool.getMetrics().orElseThrow().allocatedSize() > 0);

        long deadline = System.currentTimeMillis() + 10_000;

        while (pool.getMetrics().orElseThrow().allocatedSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }

        assertEquals(0, pool.getMetrics().orElseThrow().allocatedSize());
    }
}
