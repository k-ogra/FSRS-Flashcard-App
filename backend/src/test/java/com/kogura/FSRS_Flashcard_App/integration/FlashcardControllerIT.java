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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Full-stack integration tests for {@link com.kogura.FSRS_Flashcard_App.controller.FlashcardController}.
 *
 * <p>Every test starts from a clean database state. Test data is created exclusively through
 * the REST API so the full request/response cycle — including Spring Security, CSRF, and
 * session management — is exercised end-to-end.
 *
 * <p>{@link S3Service} and {@link MediaMetadataService} are replaced with Mockito beans so no
 * real AWS calls are made during delete or media-attach operations.
 *
 * <p>CSRF flow mirrors the real client: {@code GET /api/v0/auth/csrf} establishes an anonymous
 * pre-session; {@code POST /api/v0/auth/signup} consumes it and returns an authenticated session
 * whose CSRF token is used for all subsequent mutating requests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureRestTestClient
@Testcontainers
class FlashcardControllerIT {

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
     * Replaces the real {@link S3Service} bean so {@code DELETE /api/v0/flashcards/{id}},
     * {@code POST /api/v0/flashcards/{id}/media}, and {@code DELETE /api/v0/flashcards/{id}/media}
     * do not make real AWS S3 calls. Individual tests configure mock return values where needed.
     */
    @MockitoBean
    private S3Service s3Service;

    /**
     * Replaces the real {@link MediaMetadataService} bean so presigned-URL refresh and
     * media-attachment logic in {@code PUT} and media endpoints are no-ops.
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
     * Deletion order respects FK constraints: flashcards reference decks; decks reference users;
     * session attributes reference sessions.
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

    // ── GET /api/v0/flashcards ────────────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void getAllFlashcards_unauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/flashcards")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * The endpoint returns all flashcards across all decks with no per-user filtering.
     * A flashcard created by alice is visible when queried with alice's session.
     */
    @Test
    void getAllFlashcards_authenticated_returnsFlashcards() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        createFlashcard(alice, deckId, "Q1", "A1");

        restTestClient.get()
                .uri("/api/v0/flashcards")
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].question").isEqualTo("Q1")
                .jsonPath("$[0].answer").isEqualTo("A1");
    }

    // ── GET /api/v0/flashcards/{id} ───────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void getFlashcardById_unauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/flashcards/1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Fetching an existing flashcard by ID returns 200 with its question and answer. */
    @Test
    void getFlashcardById_exists_returns200WithContent() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "What is Java?", "A language");

        restTestClient.get()
                .uri("/api/v0/flashcards/{id}", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(cardId)
                .jsonPath("$.question").isEqualTo("What is Java?")
                .jsonPath("$.answer").isEqualTo("A language");
    }

    /** Requesting a non-existent flashcard ID returns 404. */
    @Test
    void getFlashcardById_notFound_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/flashcards/99999")
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── POST /api/v0/flashcards ───────────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void createFlashcard_unauthenticated_returns401() {
        restTestClient.post()
                .uri("/api/v0/flashcards")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("deckId", 1, "question", "Q", "answer", "A"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Successful creation returns 200 with the persisted flashcard, its generated ID, question, and answer. */
    @Test
    void createFlashcard_success_returns200WithIdAndContent() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");

        restTestClient.post()
                .uri("/api/v0/flashcards")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("deckId", deckId, "question", "What is 2+2?", "answer", "4"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.question").isEqualTo("What is 2+2?")
                .jsonPath("$.answer").isEqualTo("4");
    }

    /** A request with a missing {@code question} field returns 400. */
    @Test
    void createFlashcard_missingQuestion_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");

        restTestClient.post()
                .uri("/api/v0/flashcards")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("deckId", deckId, "answer", "A"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** A request with a missing {@code answer} field returns 400. */
    @Test
    void createFlashcard_missingAnswer_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");

        restTestClient.post()
                .uri("/api/v0/flashcards")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("deckId", deckId, "question", "Q"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** A request with a missing {@code deckId} field returns 400. */
    @Test
    void createFlashcard_missingDeckId_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.post()
                .uri("/api/v0/flashcards")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "Q", "answer", "A"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** Creating a flashcard for a deck that does not exist returns 404. */
    @Test
    void createFlashcard_nonExistentDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.post()
                .uri("/api/v0/flashcards")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("deckId", 99999, "question", "Q", "answer", "A"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── PUT /api/v0/flashcards/{id} ───────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void updateFlashcard_unauthenticated_returns401() {
        restTestClient.put()
                .uri("/api/v0/flashcards/1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "New Q"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Updating the question field returns 200 with the new question value. */
    @Test
    void updateFlashcard_updatesQuestion_returns200() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Old Q", "A");

        restTestClient.put()
                .uri("/api/v0/flashcards/{id}", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "New Q", "answer", "A"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.question").isEqualTo("New Q")
                .jsonPath("$.answer").isEqualTo("A");
    }

    /** Updating the answer field returns 200 with the new answer value. */
    @Test
    void updateFlashcard_updatesAnswer_returns200() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "Old A");

        restTestClient.put()
                .uri("/api/v0/flashcards/{id}", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "Q", "answer", "New A"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.question").isEqualTo("Q")
                .jsonPath("$.answer").isEqualTo("New A");
    }

    /**
     * When only {@code question} is provided in the request body (no {@code answer}),
     * the existing answer is preserved and only the question is changed.
     */
    @Test
    void updateFlashcard_questionOnlyUpdate_preservesAnswer() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Old Q", "Original Answer");

        restTestClient.put()
                .uri("/api/v0/flashcards/{id}", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "Updated Q"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.question").isEqualTo("Updated Q")
                .jsonPath("$.answer").isEqualTo("Original Answer");
    }

    /** Attempting to update a non-existent flashcard returns 404. */
    @Test
    void updateFlashcard_notFound_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.put()
                .uri("/api/v0/flashcards/99999")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "Q", "answer", "A"))
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * Attempting to update a flashcard that belongs to another user's deck returns 404
     * rather than 403 to prevent resource enumeration.
     */
    @Test
    void updateFlashcard_otherUsersDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");

        restTestClient.put()
                .uri("/api/v0/flashcards/{id}", cardId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "Hacked Q", "answer", "Hacked A"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── DELETE /api/v0/flashcards/{id} ────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void deleteFlashcard_unauthenticated_returns401() {
        restTestClient.delete()
                .uri("/api/v0/flashcards/1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Deleting an owned flashcard returns 204. A subsequent GET for the same ID returns 404,
     * confirming the flashcard was removed.
     */
    @Test
    void deleteFlashcard_success_returns204AndFlashcardGone() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");

        restTestClient.delete()
                .uri("/api/v0/flashcards/{id}", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .exchange()
                .expectStatus().isNoContent();

        restTestClient.get()
                .uri("/api/v0/flashcards/{id}", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isNotFound();
    }

    /** Deleting a non-existent flashcard returns 404. */
    @Test
    void deleteFlashcard_notFound_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.delete()
                .uri("/api/v0/flashcards/99999")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    /** Attempting to delete a flashcard owned by another user returns 404. */
    @Test
    void deleteFlashcard_otherUsersDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");

        restTestClient.delete()
                .uri("/api/v0/flashcards/{id}", cardId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── POST /api/v0/flashcards/{id}/media ────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void attachMedia_unauthenticated_returns401() {
        restTestClient.post()
                .uri("/api/v0/flashcards/1/media")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("side", "question", "s3ObjectKey", "key", "fileName", "img.jpg"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * A {@code side} value other than {@code "question"} or {@code "answer"} returns 400.
     */
    @Test
    void attachMedia_invalidSide_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");

        restTestClient.post()
                .uri("/api/v0/flashcards/{id}/media", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("side", "front", "s3ObjectKey", "key", "fileName", "img.jpg"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /**
     * A request with a blank {@code s3ObjectKey} returns 400.
     */
    @Test
    void attachMedia_blankS3Key_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");

        restTestClient.post()
                .uri("/api/v0/flashcards/{id}/media", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("side", "question", "s3ObjectKey", "", "fileName", "img.jpg"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** Attaching media to a non-existent flashcard returns 404. */
    @Test
    void attachMedia_flashcardNotFound_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.post()
                .uri("/api/v0/flashcards/99999/media")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("side", "question", "s3ObjectKey", "some-key", "fileName", "img.jpg"))
                .exchange()
                .expectStatus().isNotFound();
    }

    /** Attaching media to a flashcard in another user's deck returns 404. */
    @Test
    void attachMedia_otherUsersDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");
        when(s3Service.getObjectMetadata(anyString()))
                .thenReturn(Map.of("flashcardid", String.valueOf(cardId), "isquestion", "true"));

        restTestClient.post()
                .uri("/api/v0/flashcards/{id}/media", cardId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("side", "question", "s3ObjectKey", "uploads/alice/key.jpg", "fileName", "img.jpg"))
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * When the S3 object metadata contains a different {@code flashcardid} than the path
     * variable, the server rejects the request with 403 to prevent cross-flashcard media
     * reassignment.
     */
    @Test
    void attachMedia_metadataMismatch_returns403() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");
        String s3Key = "uploads/alice/some-key.jpg";
        when(s3Service.getObjectMetadata(s3Key))
                .thenReturn(Map.of("flashcardid", "99999", "isquestion", "true"));

        restTestClient.post()
                .uri("/api/v0/flashcards/{id}/media", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("side", "question", "s3ObjectKey", s3Key, "fileName", "img.jpg"))
                .exchange()
                .expectStatus().isForbidden();
    }

    /**
     * When {@link S3Service#getObjectMetadata} throws an exception (e.g., object does not
     * exist), the endpoint returns 400.
     */
    @Test
    void attachMedia_s3Failure_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");
        when(s3Service.getObjectMetadata(anyString()))
                .thenThrow(new RuntimeException("S3 unavailable"));

        restTestClient.post()
                .uri("/api/v0/flashcards/{id}/media", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("side", "question", "s3ObjectKey", "uploads/alice/key.jpg", "fileName", "img.jpg"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /**
     * When S3 metadata matches the flashcard ID and side, the endpoint returns 200.
     * {@link MediaMetadataService#attachMediaToFlashcard} is mocked so no real attachment occurs;
     * the test only verifies the happy-path status code.
     */
    @Test
    void attachMedia_validMetadata_returns200() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");
        String s3Key = "uploads/alice/key.jpg";
        when(s3Service.getObjectMetadata(s3Key))
                .thenReturn(Map.of("flashcardid", String.valueOf(cardId), "isquestion", "true"));

        restTestClient.post()
                .uri("/api/v0/flashcards/{id}/media", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("side", "question", "s3ObjectKey", s3Key, "fileName", "img.jpg"))
                .exchange()
                .expectStatus().isOk();
    }

    // ── DELETE /api/v0/flashcards/{id}/media ──────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void deleteFlashcardMedia_unauthenticated_returns401() {
        restTestClient.delete()
                .uri("/api/v0/flashcards/1/media?side=question")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * A {@code side} query parameter value other than {@code "question"} or {@code "answer"}
     * returns 400.
     */
    @Test
    void deleteFlashcardMedia_invalidSide_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");

        restTestClient.delete()
                .uri("/api/v0/flashcards/{id}/media?side=front", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** Deleting media for a non-existent flashcard returns 404. */
    @Test
    void deleteFlashcardMedia_flashcardNotFound_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.delete()
                .uri("/api/v0/flashcards/99999/media?side=question")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    /** Deleting media on a flashcard in another user's deck returns 404. */
    @Test
    void deleteFlashcardMedia_otherUsersDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");

        restTestClient.delete()
                .uri("/api/v0/flashcards/{id}/media?side=question", cardId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * Deleting media on a side that has no attached media is a no-op and returns 204.
     * The controller short-circuits when {@code meta == null} without calling S3.
     */
    @Test
    void deleteFlashcardMedia_noMediaAttached_returns204() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        Long cardId = createFlashcard(alice, deckId, "Q", "A");

        restTestClient.delete()
                .uri("/api/v0/flashcards/{id}/media?side=question", cardId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .exchange()
                .expectStatus().isNoContent();
    }
}
