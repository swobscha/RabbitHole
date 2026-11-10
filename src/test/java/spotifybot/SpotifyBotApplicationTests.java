package spotifybot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "spring.r2dbc.url=r2dbc:h2:mem:///contextloads;DB_CLOSE_DELAY=-1")
class SpotifyBotApplicationTests {

	@Test
	void contextLoads() {
	}

}
