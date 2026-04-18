package com.kogura.FSRS_Flashcard_App;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test that verifies the Spring application context loads successfully.
 * Fails fast if any bean definition is broken, a required property is missing,
 * or a dependency cannot be satisfied.
 */
@SpringBootTest
@ActiveProfiles("test")
class FsrsFlashcardAppApplicationTests {

	@Test
	void contextLoads() {
	}

}
