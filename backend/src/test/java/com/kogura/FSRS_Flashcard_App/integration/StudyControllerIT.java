package com.kogura.FSRS_Flashcard_App.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserSettingsRepository;
import com.kogura.FSRS_Flashcard_App.service.MediaMetadataService;
import com.kogura.FSRS_Flashcard_App.service.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack integration tests for {@link com.kogura.FSRS_Flashcard_App.controller.StudyController}.
 *
 * <p>Both endpoints are exercised end-to-end through the real Spring Security filter chain,
 * CSRF validation, session management, and the live FSRS scheduling algorithm. A real
 * PostgreSQL Testcontainer is used so repository queries and the daily-progress tracking
 * run against an actual database.
 *
 * <p>{@link S3Service} and {@link MediaMetadataService} are replaced with Mockito beans.
 * {@code MediaMetadataService} is mocked because {@code StudyService.buildStudyDTO()} calls
 * {@code refreshDownloadUrlIfNeeded}; for flashcards without attached media the null-guard
 * short-circuits before the mock is reached, but mocking ensures no real AWS call can slip
 * through if test data is extended later.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureRestTestClient
@Testcontainers
class StudyControllerIT {

    /** Injected random port chosen by the embedded server at startup. */
    @LocalServerPort
    private int port;

    /**
     * Singleton PostgreSQL container shared across all tests in this JVM.
     * Started once via a static initializer to avoid per-test container overhead.
     */
    @SuppressWarnings("resource")
    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    /**
     * Overrides Spring datasource properties with Testcontainer-provided values.
     * AWS stub values are provided because the real {@link S3Service} is replaced by a mock.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("aws.s3.region", () -> "us-east-1");
        registry.add("aws.s3.bucket.name", () -> "test-bucket");
    }

    /**
     * Replaces the real {@link S3Service} bean so no AWS calls are made.
     * The study endpoints do not call S3 directly; this mock is present for safety.
     */
    @MockitoBean
    private S3Service s3Service;

    /**
     * Replaces the real {@link MediaMetadataService} bean so presigned-URL refresh inside
     * {@code StudyService.buildStudyDTO()} is a no-op. The mock returns {@code null} by
     * default, which is safe because the DTO builder only reads from the returned value when
     * the flashcard already has an S3 key — test flashcards have no media attached.
     */
    @MockitoBean
    private MediaMetadataService mediaMetadataService;

    /** REST client rebuilt against the random port before every test. */
    @Autowired
    private RestTestClient restTestClient;

    /** Used to look up persisted users during test assertions. */
    @Autowired
    private UserRepository userRepository;

    /** Deleted during {@link #setUp()} to avoid FK violations when wiping users. */
    @Autowired
    private UserSettingsRepository userSettingsRepository;

    /** Used to issue direct SQL deletes in FK-safe order during {@link #setUp()}. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Jackson mapper for parsing response bodies. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Setup ─────────────────────────────────────────────────────────────────

    /**
     * Wipes all application and session data before each test.
     * Deletion order respects FK constraints: progress and shares reference decks;
     * decks reference users; session attributes reference sessions.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM daily_study_progress");
        jdbcTemplate.update("DELETE FROM shared_decks");
        jdbcTemplate.update("DELETE FROM flashcards");
        jdbcTemplate.update("DELETE FROM decks");
        userSettingsRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM spring_session_attributes");
        jdbcTemplate.update("DELETE FROM spring_session");
        userRepository.deleteAll();
        restTestClient = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Anonymous SESSION cookie paired with its one-time CSRF token from {@code GET /csrf}. */
    record PreSession(String sessionCookie, String csrfToken) {}

    /** Authenticated SESSION cookie paired with the session's CSRF token from signup/login. */
    record UserSession(String sessionCookie, String csrfToken) {}

    /**
     * Obtains an anonymous pre-session and CSRF token from {@code GET /api/v0/auth/csrf}.
     */
    private PreSession getPreSession() throws Exception {
        EntityExchangeResult<String> result = restTestClient.get()
                .uri("/api/v0/auth/csrf")
                .exchange()
                .returnResult(String.class);
        String csrfToken = objectMapper.readTree(result.getResponseBody()).get("token").asText();
        ResponseCookie session = result.getResponseCookies().getFirst("SESSION");
        return new PreSession(session != null ? session.getValue() : null, csrfToken);
    }

    /**
     * Registers a new user via {@code POST /api/v0/auth/signup} and returns the resulting
     * authenticated session. Consumes a pre-session internally.
     */
    private UserSession signupUser(String username, String password) throws Exception {
        PreSession pre = getPreSession();
        EntityExchangeResult<String> result = restTestClient.post()
                .uri("/api/v0/auth/signup")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "password", password))
                .exchange()
                .returnResult(String.class);
        ResponseCookie session = result.getResponseCookies().getFirst("SESSION");
        return new UserSession(
                session != null ? session.getValue() : null,
                result.getResponseHeaders().getFirst("X-CSRF-TOKEN"));
    }

    /**
     * Creates a deck with the given name for the authenticated session and returns its ID.
     */
    private Long createDeck(UserSession session, String name) throws Exception {
        EntityExchangeResult<String> result = restTestClient.post()
                .uri("/api/v0/decks")
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        return objectMapper.readTree(result.getResponseBody()).get("id").asLong();
    }

    /**
     * Creates a flashcard in the specified deck and returns the persisted flashcard ID.
     */
    private Long createFlashcard(UserSession session, Long deckId, String question, String answer) throws Exception {
        EntityExchangeResult<String> result = restTestClient.post()
                .uri("/api/v0/flashcards")
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("deckId", deckId, "question", question, "answer", answer))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        return objectMapper.readTree(result.getResponseBody()).get("id").asLong();
    }

    // ── GET /api/v0/decks/{deckId}/study/queue ────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void getStudyQueue_unauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/decks/1/study/queue")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Requesting the study queue for a non-existent deck returns 404. */
    @Test
    void getStudyQueue_deckNotFound_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/decks/99999/study/queue")
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * Requesting the study queue for another user's deck returns 404 rather than 403
     * to prevent deck ID enumeration.
     */
    @Test
    void getStudyQueue_otherUsersDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");

        restTestClient.get()
                .uri("/api/v0/decks/{deckId}/study/queue", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .exchange()
                .expectStatus().isNotFound();
    }

    /** A deck with no flashcards returns 200 with an empty study queue. */
    @Test
    void getStudyQueue_emptyDeck_returns200WithEmptyList() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "Empty Deck");

        restTestClient.get()
                .uri("/api/v0/decks/{deckId}/study/queue", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    /**
     * New flashcards (never reviewed) appear in the study queue with state {@code "NEW"}
     * and their question and answer text intact.
     */
    @Test
    void getStudyQueue_newFlashcards_appearsWithStateNew() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "What is Java?", "A language");

        restTestClient.get()
                .uri("/api/v0/decks/{deckId}/study/queue", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(cardId)
                .jsonPath("$[0].question").isEqualTo("What is Java?")
                .jsonPath("$[0].answer").isEqualTo("A language")
                .jsonPath("$[0].state").isEqualTo("NEW");
    }

    /**
     * Each entry in the study queue carries four interval-preview fields — {@code againInterval},
     * {@code hardInterval}, {@code goodInterval}, and {@code easyInterval} — computed by the
     * FSRS scheduler. These allow the frontend to show the user how each rating affects the
     * next due date before they commit to a rating.
     */
    @Test
    void getStudyQueue_newFlashcard_hasAllFourIntervalPreviews() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        createFlashcard(alice, deckId, "Q", "A");

        restTestClient.get()
                .uri("/api/v0/decks/{deckId}/study/queue", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].againInterval").exists()
                .jsonPath("$[0].hardInterval").exists()
                .jsonPath("$[0].goodInterval").exists()
                .jsonPath("$[0].easyInterval").exists();
    }

    /**
     * Only flashcards belonging to the requested deck appear in the queue — cards from
     * other decks owned by the same user are excluded.
     */
    @Test
    void getStudyQueue_returnsOnlyCardsForRequestedDeck() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckA = createDeck(alice, "Deck A");
        Long deckB = createDeck(alice, "Deck B");
        Long cardA = createFlashcard(alice, deckA, "Q-A", "Ans-A");
        createFlashcard(alice, deckB, "Q-B", "Ans-B");

        restTestClient.get()
                .uri("/api/v0/decks/{deckId}/study/queue", deckA)
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(cardA);
    }

    // ── POST /api/v0/decks/{deckId}/study/review ──────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void reviewCard_unauthenticated_returns401() {
        restTestClient.post()
                .uri("/api/v0/decks/1/study/review")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flashcardId", 1, "grade", "GOOD"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Submitting a review against a non-existent deck returns 404. */
    @Test
    void reviewCard_deckNotFound_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.post()
                .uri("/api/v0/decks/99999/study/review")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flashcardId", 1, "grade", "GOOD"))
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * Submitting a review against another user's deck returns 404 rather than 403
     * to prevent deck ID enumeration.
     */
    @Test
    void reviewCard_otherUsersDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");

        restTestClient.post()
                .uri("/api/v0/decks/{deckId}/study/review", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flashcardId", cardId, "grade", "GOOD"))
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * Submitting a review using a flashcard ID that belongs to a different deck returns 400.
     * The controller validates that the flashcard's parent deck matches the path {@code deckId}.
     */
    @Test
    void reviewCard_flashcardNotInDeck_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckA = createDeck(alice, "Deck A");
        Long deckB = createDeck(alice, "Deck B");
        Long cardInDeckB = createFlashcard(alice, deckB, "Q", "A");

        restTestClient.post()
                .uri("/api/v0/decks/{deckId}/study/review", deckA)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flashcardId", cardInDeckB, "grade", "GOOD"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /**
     * Reviewing a new card with a GOOD rating returns 200 and transitions the card out of
     * the {@code "NEW"} state. The response body is a {@link com.kogura.FSRS_Flashcard_App.dto.FlashcardStudyDTO}
     * with the card's updated FSRS scheduling fields.
     */
    @Test
    void reviewCard_goodRating_returns200AndTransitionsFromNew() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "What is 2+2?", "4");

        EntityExchangeResult<String> result = restTestClient.post()
                .uri("/api/v0/decks/{deckId}/study/review", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flashcardId", cardId, "grade", "GOOD"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        var body = objectMapper.readTree(result.getResponseBody());
        assertThat(body.get("id").asLong()).isEqualTo(cardId);
        assertThat(body.get("state").asText()).isNotEqualTo("NEW");
        assertThat(body.get("question").asText()).isEqualTo("What is 2+2?");
        assertThat(body.get("answer").asText()).isEqualTo("4");
    }

    /**
     * Reviewing with an AGAIN rating returns 200 and provides all four interval previews
     * reflecting how the FSRS scheduler would schedule the card for each possible next rating.
     */
    @Test
    void reviewCard_againRating_returns200WithIntervalPreviews() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");

        restTestClient.post()
                .uri("/api/v0/decks/{deckId}/study/review", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flashcardId", cardId, "grade", "AGAIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(cardId)
                .jsonPath("$.againInterval").exists()
                .jsonPath("$.hardInterval").exists()
                .jsonPath("$.goodInterval").exists()
                .jsonPath("$.easyInterval").exists();
    }

    /**
     * Reviewing a card twice in sequence is valid: a second review immediately after the
     * first returns 200 without error. This exercises the branch where {@code wasNew} is
     * false and the card is in a LEARNING or REVIEW state.
     */
    @Test
    void reviewCard_reviewedTwice_secondReviewReturns200() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");

        // First review — transitions from NEW
        restTestClient.post()
                .uri("/api/v0/decks/{deckId}/study/review", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flashcardId", cardId, "grade", "GOOD"))
                .exchange()
                .expectStatus().isOk();

        // Second review — card is now in a non-NEW state
        restTestClient.post()
                .uri("/api/v0/decks/{deckId}/study/review", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flashcardId", cardId, "grade", "EASY"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(cardId);
    }
}
