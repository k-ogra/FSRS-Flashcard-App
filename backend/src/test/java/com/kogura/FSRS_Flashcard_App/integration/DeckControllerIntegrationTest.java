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
 * Full-stack integration tests for {@link com.kogura.FSRS_Flashcard_App.controller.DeckController}.
 *
 * <p>Every test starts from a clean database: sessions, shares, decks, and users are wiped in
 * {@link #setUp()} before each test. Test data is created exclusively through the REST API so
 * the full request/response cycle — including Spring Security, CSRF, and session management —
 * is exercised end-to-end.
 *
 * <p>{@link S3Service} and {@link MediaMetadataService} are replaced with Mockito beans so no
 * real AWS calls are made during deck delete or copy operations.
 *
 * <p>CSRF flow mirrors the real client: {@code GET /api/v0/auth/csrf} establishes an anonymous
 * pre-session with a one-time token; {@code POST /api/v0/auth/signup} consumes it and returns an
 * authenticated session whose CSRF token is used for all subsequent mutating requests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureRestTestClient
@Testcontainers
class DeckControllerIntegrationTest {

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
     * Replaces the real {@link S3Service} bean so {@code DELETE /api/v0/decks/{id}} and
     * {@code POST /api/v0/decks/{id}/copy} do not make real AWS S3 calls.
     */
    @MockitoBean
    private S3Service s3Service;

    /**
     * Replaces the real {@link MediaMetadataService} bean so presigned-URL refresh in
     * {@code GET /api/v0/decks/{id}} and media copying in {@code POST /api/v0/decks/{id}/copy}
     * are no-ops.
     */
    @MockitoBean
    private MediaMetadataService mediaMetadataService;

    /** REST client rebuilt against the random port before every test. */
    @Autowired
    private RestTestClient restTestClient;

    /** Used to resolve user IDs required for share/unshare path variables. */
    @Autowired
    private UserRepository userRepository;

    /** Deleted during {@link #setUp()} to avoid FK violations when wiping users. */
    @Autowired
    private UserSettingsRepository userSettingsRepository;

    /** Used to issue direct SQL deletes in FK-safe order during {@link #setUp()}. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Setup ─────────────────────────────────────────────────────────────────

    /**
     * Wipes all application and session data before each test.
     * Deletion order respects FK constraints: shares and study-progress reference decks;
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
     * Creates a deck with the given name for the authenticated session.
     *
     * @return the persisted deck's {@code id}
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
     * Shares the given deck with {@code recipientUsername} as the authenticated owner.
     */
    private void shareDeck(UserSession ownerSession, Long deckId, String recipientUsername) {
        restTestClient.post()
                .uri("/api/v0/decks/{id}/share", deckId)
                .cookie("SESSION", ownerSession.sessionCookie())
                .header("X-XSRF-TOKEN", ownerSession.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", recipientUsername))
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * Creates a flashcard with the given question and answer in the specified deck.
     *
     * @return the persisted flashcard's {@code id}
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

    /**
     * Sets the given deck's visibility to public via {@code PATCH /api/v0/decks/{id}/visibility}.
     */
    private void makeDeckPublic(UserSession session, Long deckId) {
        restTestClient.patch()
                .uri("/api/v0/decks/{id}/visibility", deckId)
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("isPublic", true))
                .exchange()
                .expectStatus().isOk();
    }

    // ── GET /api/v0/decks ─────────────────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void getAllDecks_unauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/decks")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Authenticated user sees only their own decks, not other users' decks. */
    @Test
    void getAllDecks_returnsOnlyOwnDecks() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        createDeck(alice, "Alice Deck");
        createDeck(bob, "Bob Deck");

        restTestClient.get()
                .uri("/api/v0/decks")
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].name").isEqualTo("Alice Deck");
    }

    /**
     * The {@code shared} transient field must be {@code true} when at least one
     * {@code SharedDeck} record exists for the deck.
     */
    @Test
    void getAllDecks_sharedDeck_hasSharedFieldTrue() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Shared Deck");
        shareDeck(alice, deckId, "bob");

        restTestClient.get()
                .uri("/api/v0/decks")
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].isShared").isEqualTo(true);
    }

    // ── GET /api/v0/decks/stats ───────────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void getAllDeckStats_unauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/decks/stats")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Stats response is a JSON object keyed by deck ID, each value containing
     * {@code newCount}, {@code learningCount}, and {@code reviewCount}.
     */
    @Test
    void getAllDeckStats_returnsStatsKeyedByDeckId() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");

        restTestClient.get()
                .uri("/api/v0/decks/stats")
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$." + deckId).exists()
                .jsonPath("$." + deckId + ".newCount").isEqualTo(0)
                .jsonPath("$." + deckId + ".learningCount").isEqualTo(0)
                .jsonPath("$." + deckId + ".reviewCount").isEqualTo(0);
    }

    // ── GET /api/v0/decks/public ──────────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void getPublicDecks_unauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/decks/public")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Only decks explicitly marked public appear in the public listing; private decks are excluded. */
    @Test
    void getPublicDecks_returnsOnlyPublicDecks() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long publicId = createDeck(alice, "Public Deck");
        createDeck(alice, "Private Deck");
        makeDeckPublic(alice, publicId);

        restTestClient.get()
                .uri("/api/v0/decks/public")
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].name").isEqualTo("Public Deck");
    }

    // ── GET /api/v0/decks/shared ──────────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void getSharedDecks_unauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/decks/shared")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** A user who has had no decks shared with them sees an empty list. */
    @Test
    void getSharedDecks_noShares_returnsEmptyList() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/decks/shared")
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    /**
     * User sees decks shared with them by others, including the sharer's username
     * in the {@code sharedByUsername} field.
     */
    @Test
    void getSharedDecks_returnsDecksSharedWithUser() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");
        shareDeck(alice, deckId, "bob");

        restTestClient.get()
                .uri("/api/v0/decks/shared")
                .cookie("SESSION", bob.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].name").isEqualTo("Alice's Deck")
                .jsonPath("$[0].sharedByUsername").isEqualTo("alice");
    }

    // ── GET /api/v0/decks/{id} ────────────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void getDeckById_unauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/decks/1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Owner can retrieve their own deck by ID with its name and ID intact. */
    @Test
    void getDeckById_owner_returns200() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");

        restTestClient.get()
                .uri("/api/v0/decks/{id}", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(deckId)
                .jsonPath("$.name").isEqualTo("My Deck");
    }

    /** A non-existent deck ID must return 404. */
    @Test
    void getDeckById_notFound_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/decks/99999")
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * Requesting another user's private, unshared deck returns 404 rather than 403
     * to prevent deck ID enumeration.
     */
    @Test
    void getDeckById_privateUnsharedDeck_returns404ForOtherUser() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Private Deck");

        restTestClient.get()
                .uri("/api/v0/decks/{id}", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .exchange()
                .expectStatus().isNotFound();
    }

    /** A public deck is accessible to any authenticated user, not just the owner. */
    @Test
    void getDeckById_publicDeck_accessibleByAnyUser() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Public Deck");
        makeDeckPublic(alice, deckId);

        restTestClient.get()
                .uri("/api/v0/decks/{id}", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(deckId);
    }

    /** A deck explicitly shared with a user is accessible to that user. */
    @Test
    void getDeckById_sharedDeck_accessibleByRecipient() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Shared Deck");
        shareDeck(alice, deckId, "bob");

        restTestClient.get()
                .uri("/api/v0/decks/{id}", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(deckId);
    }

    // ── POST /api/v0/decks ────────────────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void createDeck_unauthenticated_returns401() {
        restTestClient.post()
                .uri("/api/v0/decks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "My Deck"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Successful deck creation returns 200 with the persisted deck and its generated ID. */
    @Test
    void createDeck_success_returns200WithId() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.post()
                .uri("/api/v0/decks")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "My Deck"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.name").isEqualTo("My Deck");
    }

    /** A blank deck name must be rejected with 400. */
    @Test
    void createDeck_emptyName_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.post()
                .uri("/api/v0/decks")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", ""))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** Creating a second deck with the same name for the same user must return 409. */
    @Test
    void createDeck_duplicateName_returns409() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        createDeck(alice, "Dup Deck");

        restTestClient.post()
                .uri("/api/v0/decks")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Dup Deck"))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ── DELETE /api/v0/decks/{id} ─────────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void deleteDeck_unauthenticated_returns401() {
        restTestClient.delete()
                .uri("/api/v0/decks/1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Deleting an owned deck returns 204 and a subsequent GET for the same ID returns 404,
     * confirming the deck was removed.
     */
    @Test
    void deleteDeck_success_returns204AndDeckNoLongerExists() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "To Delete");

        restTestClient.delete()
                .uri("/api/v0/decks/{id}", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .exchange()
                .expectStatus().isNoContent();

        restTestClient.get()
                .uri("/api/v0/decks/{id}", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isNotFound();
    }

    /** Deleting a non-existent deck returns 404. */
    @Test
    void deleteDeck_notFound_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");

        restTestClient.delete()
                .uri("/api/v0/decks/99999")
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    /** Attempting to delete another user's deck returns 404. */
    @Test
    void deleteDeck_otherUsersDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");

        restTestClient.delete()
                .uri("/api/v0/decks/{id}", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── PATCH /api/v0/decks/{id}/visibility ───────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void toggleVisibility_unauthenticated_returns401() {
        restTestClient.patch()
                .uri("/api/v0/decks/1/visibility")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("isPublic", true))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Setting {@code isPublic} to {@code true} returns 200 with {@code isPublic: true}. */
    @Test
    void toggleVisibility_setPublic_returns200() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");

        restTestClient.patch()
                .uri("/api/v0/decks/{id}/visibility", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("isPublic", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.isPublic").isEqualTo(true);
    }

    /** Setting {@code isPublic} to {@code false} on a public deck returns 200 with {@code isPublic: false}. */
    @Test
    void toggleVisibility_setPrivate_returns200() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");
        makeDeckPublic(alice, deckId);

        restTestClient.patch()
                .uri("/api/v0/decks/{id}/visibility", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("isPublic", false))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.isPublic").isEqualTo(false);
    }

    /** Changing visibility on another user's deck returns 404. */
    @Test
    void toggleVisibility_otherUsersDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");

        restTestClient.patch()
                .uri("/api/v0/decks/{id}/visibility", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("isPublic", true))
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── GET /api/v0/decks/{id}/share ──────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void getDeckRecipients_unauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/decks/1/share")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** A newly created deck with no shares returns an empty recipient list. */
    @Test
    void getDeckRecipients_noShares_returnsEmptyList() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");

        restTestClient.get()
                .uri("/api/v0/decks/{id}/share", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    /** After sharing, the recipient appears in the list with their username and ID. */
    @Test
    void getDeckRecipients_withShare_returnsRecipient() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Shared Deck");
        shareDeck(alice, deckId, "bob");

        restTestClient.get()
                .uri("/api/v0/decks/{id}/share", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].username").isEqualTo("bob");
    }

    /** Requesting the share list for another user's deck returns 404. */
    @Test
    void getDeckRecipients_otherUsersDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");

        restTestClient.get()
                .uri("/api/v0/decks/{id}/share", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── POST /api/v0/decks/{id}/share ─────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void shareDeck_unauthenticated_returns401() {
        restTestClient.post()
                .uri("/api/v0/decks/1/share")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "bob"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Sharing a deck with an existing user returns 200 with a success message. */
    @Test
    void shareDeck_success_returns200() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "My Deck");

        restTestClient.post()
                .uri("/api/v0/decks/{id}/share", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "bob"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Deck shared successfully");
    }

    /** Sharing with a username that does not exist returns 400. */
    @Test
    void shareDeck_nonExistentRecipient_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");

        restTestClient.post()
                .uri("/api/v0/decks/{id}/share", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "ghost"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** Sharing a deck with yourself returns 400. */
    @Test
    void shareDeck_shareWithSelf_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");

        restTestClient.post()
                .uri("/api/v0/decks/{id}/share", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** Sharing a deck with a user it is already shared with returns 409. */
    @Test
    void shareDeck_alreadyShared_returns409() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "My Deck");
        shareDeck(alice, deckId, "bob");

        restTestClient.post()
                .uri("/api/v0/decks/{id}/share", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "bob"))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    /** Sharing another user's deck returns 404. */
    @Test
    void shareDeck_otherUsersDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        signupUser("charlie", "pass3");
        Long deckId = createDeck(alice, "Alice's Deck");

        restTestClient.post()
                .uri("/api/v0/decks/{id}/share", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "charlie"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── DELETE /api/v0/decks/{id}/share/{userId} ──────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void unshareDeck_unauthenticated_returns401() {
        restTestClient.delete()
                .uri("/api/v0/decks/1/share/1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Removing a share returns 204. The deck no longer appears in the recipient's
     * shared-deck listing.
     */
    @Test
    void unshareDeck_success_returns204() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Shared Deck");
        shareDeck(alice, deckId, "bob");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();

        restTestClient.delete()
                .uri("/api/v0/decks/{id}/share/{userId}", deckId, bobId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .exchange()
                .expectStatus().isNoContent();

        restTestClient.get()
                .uri("/api/v0/decks/shared")
                .cookie("SESSION", bob.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    /** Unsharing from a deck the caller does not own returns 404. */
    @Test
    void unshareDeck_otherUsersDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();

        restTestClient.delete()
                .uri("/api/v0/decks/{id}/share/{userId}", deckId, bobId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── POST /api/v0/decks/{id}/copy ──────────────────────────────────────────

    /** Unauthenticated request must be rejected with 401. */
    @Test
    void copyDeck_unauthenticated_returns401() {
        restTestClient.post()
                .uri("/api/v0/decks/1/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Copy"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** A user can copy a public deck owned by someone else into their own library. */
    @Test
    void copyDeck_fromPublicDeck_returns200() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Public Deck");
        makeDeckPublic(alice, deckId);

        restTestClient.post()
                .uri("/api/v0/decks/{id}/copy", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Bob's Copy"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Bob's Copy");
    }

    /** A user can copy a deck that has been explicitly shared with them. */
    @Test
    void copyDeck_fromSharedDeck_returns200() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Shared Deck");
        shareDeck(alice, deckId, "bob");

        restTestClient.post()
                .uri("/api/v0/decks/{id}/copy", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Bob's Copy"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Bob's Copy");
    }

    /**
     * Copying a deck produces a new deck whose flashcards contain the same question and answer
     * text as the source deck, in the same order, and are independent records with distinct IDs.
     *
     * <p>Setup: alice owns a deck with two flashcards; the deck is shared with bob. Bob copies
     * the deck, then retrieves the copy by ID and inspects its {@code flashcards} array.
     */
    @Test
    void copyDeck_flashcardsAreCopiedWithCorrectContent() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long sourceDeckId = createDeck(alice, "Alice's Deck");
        Long card1Id = createFlashcard(alice, sourceDeckId, "What is 2+2?", "4");
        Long card2Id = createFlashcard(alice, sourceDeckId, "Capital of France?", "Paris");
        shareDeck(alice, sourceDeckId, "bob");

        EntityExchangeResult<String> copyResult = restTestClient.post()
                .uri("/api/v0/decks/{id}/copy", sourceDeckId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Bob's Copy"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        Long copiedDeckId = objectMapper.readTree(copyResult.getResponseBody()).get("id").asLong();

        restTestClient.get()
                .uri("/api/v0/decks/{id}", copiedDeckId)
                .cookie("SESSION", bob.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(copiedDeckId)
                .jsonPath("$.name").isEqualTo("Bob's Copy")
                .jsonPath("$.flashcards.length()").isEqualTo(2)
                .jsonPath("$.flashcards[0].question").isEqualTo("What is 2+2?")
                .jsonPath("$.flashcards[0].answer").isEqualTo("4")
                .jsonPath("$.flashcards[1].question").isEqualTo("Capital of France?")
                .jsonPath("$.flashcards[1].answer").isEqualTo("Paris");

        // Copied flashcard IDs must differ from the source IDs — they are independent records
        assertThat(objectMapper.readTree(copyResult.getResponseBody())
                .path("flashcards").findValuesAsText("id"))
                .doesNotContain(card1Id.toString(), card2Id.toString());
    }

    /** A user cannot copy a deck they own themselves. */
    @Test
    void copyDeck_ownDeck_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        Long deckId = createDeck(alice, "My Deck");

        restTestClient.post()
                .uri("/api/v0/decks/{id}/copy", deckId)
                .cookie("SESSION", alice.sessionCookie())
                .header("X-XSRF-TOKEN", alice.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "My Copy"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** Copying a private deck that has not been shared with the caller returns 404. */
    @Test
    void copyDeck_privateUnsharedDeck_returns404() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Private Deck");

        restTestClient.post()
                .uri("/api/v0/decks/{id}/copy", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Bob's Copy"))
                .exchange()
                .expectStatus().isNotFound();
    }

    /** Copying a deck using a name that already exists in the caller's library returns 409. */
    @Test
    void copyDeck_duplicateName_returns409() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");
        makeDeckPublic(alice, deckId);
        createDeck(bob, "Taken Name");

        restTestClient.post()
                .uri("/api/v0/decks/{id}/copy", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Taken Name"))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    /** Copying a deck with a blank name returns 400. */
    @Test
    void copyDeck_emptyName_returns400() throws Exception {
        UserSession alice = signupUser("alice", "pass1");
        UserSession bob = signupUser("bob", "pass2");
        Long deckId = createDeck(alice, "Alice's Deck");
        makeDeckPublic(alice, deckId);

        restTestClient.post()
                .uri("/api/v0/decks/{id}/copy", deckId)
                .cookie("SESSION", bob.sessionCookie())
                .header("X-XSRF-TOKEN", bob.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", ""))
                .exchange()
                .expectStatus().isBadRequest();
    }
}
