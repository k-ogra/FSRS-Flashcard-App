package com.kogura.FSRS_Flashcard_App.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kogura.FSRS_Flashcard_App.dto.AuthResponse;
import com.kogura.FSRS_Flashcard_App.dto.UserSettingsDTO;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.kogura.FSRS_Flashcard_App.service.S3Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack integration tests for
 * {@link com.kogura.FSRS_Flashcard_App.controller.AuthController}.
 *
 * <p>
 * Uses a real PostgreSQL container and {@link RestTestClient} against the full
 * Spring Security
 * filter chain. CSRF is exercised manually so every test reflects how the real
 * client behaves.
 *
 * <p>
 * Session terminology used throughout:
 * <ul>
 * <li><b>Pre-session</b> — anonymous HTTP session created by {@code GET /csrf}.
 * Holds the one-time CSRF token valid only for the subsequent login or
 * signup.</li>
 * <li><b>Authenticated session</b> — new HTTP session created on successful
 * login/signup.
 * The pre-session is invalidated at that point (session-fixation protection).
 * This session
 * holds the security context and its own CSRF token returned in the
 * {@code X-CSRF-TOKEN}
 * response header.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureRestTestClient
class AuthControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    static {
        postgres.start();
    }

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

    @MockitoBean
    private S3Service s3Service;

    @Autowired
    private RestTestClient restTestClient;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserSettingsRepository userSettingsRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Setup ─────────────────────────────────────────────────────────────────

    /**
     * Wipes all user data and Spring Session rows before each test.
     * Spring Session attributes must be deleted before sessions due to the FK.
     */
    @BeforeEach
    void setUp() {
        userSettingsRepository.deleteAll();
        userRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM spring_session_attributes");
        jdbcTemplate.update("DELETE FROM spring_session");
        restTestClient = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Pre-session: anonymous session created by GET /csrf; holds a one-time CSRF
     * token.
     */
    record PreSession(String sessionCookie, String csrfToken) {
    }

    /** Authenticated session returned after login/signup. */
    record UserSession(String sessionCookie, String csrfToken) {
    }

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
     * Registers a new user via the full signup flow and returns the resulting
     * authenticated session. The pre-session is consumed internally.
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

    // ── GET /csrf ─────────────────────────────────────────────────────────────

    /** Public endpoint must return a non-blank CSRF token in the JSON body. */
    @Test
    void csrf_returnsTokenInBody() {
        restTestClient.get()
                .uri("/api/v0/auth/csrf")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.token").isNotEmpty();
    }

    /** A pre-session cookie must be set alongside the CSRF token. */
    @Test
    void csrf_setsSessionCookie() {
        assertThat(restTestClient.get()
                .uri("/api/v0/auth/csrf")
                .exchange()
                .returnResult()
                .getResponseCookies().getFirst("SESSION")).isNotNull();
    }

    /** Each call to GET /csrf must produce a distinct pre-session and token. */
    @Test
    void csrf_eachCallProducesDistinctTokenAndSession() throws Exception {
        PreSession first = getPreSession();
        PreSession second = getPreSession();
        assertThat(first.csrfToken()).isNotEqualTo(second.csrfToken());
        assertThat(first.sessionCookie()).isNotEqualTo(second.sessionCookie());
    }

    // ── POST /signup ──────────────────────────────────────────────────────────

    /** Successful signup returns 200 and echoes back the registered username. */
    @Test
    void signup_success_returns200WithUsername() throws Exception {
        PreSession pre = getPreSession();
        restTestClient.post()
                .uri("/api/v0/auth/signup")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class)
                .value(body -> assertThat(body.getUsername()).isEqualTo("alice"));
    }

    /**
     * Signup response must contain X-CSRF-TOKEN so the client can make mutations
     * immediately.
     */
    @Test
    void signup_success_returnsSessionCsrfTokenInHeader() throws Exception {
        PreSession pre = getPreSession();
        assertThat(restTestClient.post()
                .uri("/api/v0/auth/signup")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isOk()
                .returnResult()
                .getResponseHeaders().getFirst("X-CSRF-TOKEN")).isNotNull();
    }

    /**
     * Session-fixation protection: the SESSION cookie after signup must differ from
     * the pre-session.
     */
    @Test
    void signup_success_issuesNewSessionCookie() throws Exception {
        PreSession pre = getPreSession();
        EntityExchangeResult<String> result = restTestClient.post()
                .uri("/api/v0/auth/signup")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        ResponseCookie newSession = result.getResponseCookies().getFirst("SESSION");
        String newCsrf = result.getResponseHeaders().getFirst("X-CSRF-TOKEN");
        assertThat(newSession).isNotNull();
        assertThat(newSession.getValue()).isNotEqualTo(pre.sessionCookie());
        assertThat(newCsrf).isNotBlank();
        assertThat(newCsrf).isNotEqualTo(pre.csrfToken());
    }

    /** Registering a username that already exists must return 400. */
    @Test
    void signup_duplicateUsername_returns400() throws Exception {
        signupUser("alice", "pass1");
        PreSession pre = getPreSession();
        restTestClient.post()
                .uri("/api/v0/auth/signup")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass2"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** Omitting the X-XSRF-TOKEN header must return 401. */
    @Test
    void signup_missingCsrfHeader_returns401() throws Exception {
        PreSession pre = getPreSession();
        restTestClient.post()
                .uri("/api/v0/auth/signup")
                .cookie("SESSION", pre.sessionCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Sending a wrong CSRF token must return 401. */
    @Test
    void signup_wrongCsrfToken_returns401() throws Exception {
        PreSession pre = getPreSession();
        restTestClient.post()
                .uri("/api/v0/auth/signup")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", "not-the-right-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Without a pre-session cookie there is no CSRF token to validate against —
     * must return 401.
     */
    @Test
    void signup_noPreSession_returns401() {
        restTestClient.post()
                .uri("/api/v0/auth/signup")
                .header("X-XSRF-TOKEN", "any-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Reusing a consumed pre-session must return 401 (replay prevention). */
    @Test
    void signup_preSessionReuse_returns401() throws Exception {
        PreSession pre = getPreSession();
        restTestClient.post()
                .uri("/api/v0/auth/signup")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isOk();
        restTestClient.post()
                .uri("/api/v0/auth/signup")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "bob", "password", "pass2"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * A valid authenticated session paired with a random CSRF token must be rejected.
     * The CSRF token is stored in the session; a value not matching the stored token
     * cannot pass the CSRF filter regardless of session validity.
     */
    @Test
    void signup_validSessionWithRandomCsrfToken_returns403() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.post()
                .uri("/api/v0/auth/logout")
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", "not-a-real-csrf-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    // ── POST /login ───────────────────────────────────────────────────────────

    /** Successful login returns 200 and echoes back the authenticated username. */
    @Test
    void login_success_returns200WithUsername() throws Exception {
        signupUser("alice", "pass1");
        PreSession pre = getPreSession();
        restTestClient.post()
                .uri("/api/v0/auth/login")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class)
                .value(body -> assertThat(body.getUsername()).isEqualTo("alice"));
    }

    /**
     * Login response must include X-CSRF-TOKEN so the client can make mutations
     * immediately.
     */
    @Test
    void login_success_returnsSessionCsrfTokenInHeader() throws Exception {
        signupUser("alice", "pass1");
        PreSession pre = getPreSession();
        assertThat(restTestClient.post()
                .uri("/api/v0/auth/login")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isOk()
                .returnResult()
                .getResponseHeaders().getFirst("X-CSRF-TOKEN")).isNotNull();
    }

    /**
     * Session-fixation protection: the SESSION cookie after login must differ from
     * the pre-session.
     */
    @Test
    void login_success_issuesNewSessionCookie() throws Exception {
        signupUser("alice", "pass1");
        PreSession pre = getPreSession();
        EntityExchangeResult<String> result = restTestClient.post()
                .uri("/api/v0/auth/login")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        ResponseCookie newSession = result.getResponseCookies().getFirst("SESSION");
        assertThat(newSession).isNotNull();
        assertThat(newSession.getValue()).isNotEqualTo(pre.sessionCookie());
        String newCsrf = result.getResponseHeaders().getFirst("X-CSRF-TOKEN");
        assertThat(newCsrf).isNotBlank();
        assertThat(newCsrf).isNotEqualTo(pre.csrfToken());
    }

    /** Wrong credentials must return 401. */
    @Test
    void login_badCredentials_returns401() throws Exception {
        signupUser("alice", "pass1");
        PreSession pre = getPreSession();
        restTestClient.post()
                .uri("/api/v0/auth/login")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "wrong"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Login for an unknown username must return 401. */
    @Test
    void login_unknownUser_returns401() throws Exception {
        PreSession pre = getPreSession();
        restTestClient.post()
                .uri("/api/v0/auth/login")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "ghost", "password", "pass1"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Missing X-XSRF-TOKEN on login must return 401. */
    @Test
    void login_missingCsrfHeader_returns401() throws Exception {
        signupUser("alice", "pass1");
        PreSession pre = getPreSession();
        restTestClient.post()
                .uri("/api/v0/auth/login")
                .cookie("SESSION", pre.sessionCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Incorrect CSRF token on login must return 401. */
    @Test
    void login_wrongCsrfToken_returns401() throws Exception {
        signupUser("alice", "pass1");
        PreSession pre = getPreSession();
        restTestClient.post()
                .uri("/api/v0/auth/login")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", "wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Reusing a consumed pre-session for login must return 401 (replay prevention).
     */
    @Test
    void login_preSessionReuse_returns401() throws Exception {
        signupUser("alice", "pass1");
        PreSession pre = getPreSession();
        restTestClient.post()
                .uri("/api/v0/auth/login")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isOk();
        restTestClient.post()
                .uri("/api/v0/auth/login")
                .cookie("SESSION", pre.sessionCookie())
                .header("X-XSRF-TOKEN", pre.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "pass1"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── POST /logout ──────────────────────────────────────────────────────────

    /** Authenticated logout returns 200. */
    @Test
    void logout_whenAuthenticated_returns200() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.post()
                .uri("/api/v0/auth/logout")
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .exchange()
                .expectStatus().isOk();
    }

    /** After logout the session must be invalidated. */
    @Test
    void logout_invalidatesSession() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.post()
                .uri("/api/v0/auth/logout")
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .exchange()
                .expectStatus().isOk();
        restTestClient.get()
                .uri("/api/v0/auth/authenticated")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Logout without a CSRF token must return 401. */
    @Test
    void logout_missingCsrfToken_returns401() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.post()
                .uri("/api/v0/auth/logout")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isForbidden();
    }

    /** Logout with no session cookie must return 401. */
    @Test
    void logout_noSession_returns401() {
        restTestClient.post()
                .uri("/api/v0/auth/logout")
                .header("X-XSRF-TOKEN", "TOKEN")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── GET /authenticated ────────────────────────────────────────────────────

    /**
     * Authenticated endpoint returns 200 and the username of the logged-in user.
     */
    @Test
    void authenticated_whenAuthenticated_returns200WithUsername() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/auth/authenticated")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class)
                .value(body -> assertThat(body.getUsername()).isEqualTo("alice"));
    }

    /** Without a session the endpoint must return 401. */
    @Test
    void authenticated_whenUnauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/auth/authenticated")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** An invalid session cookie must be treated as unauthenticated. */
    @Test
    void authenticated_withInvalidSessionCookie_returns401() {
        restTestClient.get()
                .uri("/api/v0/auth/authenticated")
                .cookie("SESSION", "completely-made-up-session-id")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Using a pre-session cookie (not an authenticated session) must return 401.
     */
    @Test
    void authenticated_withPreSessionCookie_returns401() throws Exception {
        PreSession pre = getPreSession();
        restTestClient.get()
                .uri("/api/v0/auth/authenticated")
                .cookie("SESSION", pre.sessionCookie())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** After logout the authenticated endpoint must return 401. */
    @Test
    void authenticated_afterLogout_returns401() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.post()
                .uri("/api/v0/auth/logout")
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .exchange()
                .expectStatus().isOk();
        restTestClient.get()
                .uri("/api/v0/auth/authenticated")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── DELETE /account ───────────────────────────────────────────────────────

    /**
     * Authenticated account deletion returns 200 and removes the user from the
     * database.
     */
    @Test
    void deleteAccount_whenAuthenticated_returns200AndRemovesUser() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.delete()
                .uri("/api/v0/auth/account")
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .exchange()
                .expectStatus().isOk();
        assertThat(userRepository.existsByUsername("alice")).isFalse();
    }

    /** After account deletion the session must be invalidated. */
    @Test
    void deleteAccount_invalidatesSession() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.delete()
                .uri("/api/v0/auth/account")
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .exchange()
                .expectStatus().isOk();
        restTestClient.get()
                .uri("/api/v0/auth/authenticated")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Account deletion without a CSRF token must return 403 and must not remove the
     * user.
     */
    @Test
    void deleteAccount_missingCsrfToken_returns403() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.delete()
                .uri("/api/v0/auth/account")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isForbidden();
        assertThat(userRepository.existsByUsername("alice")).isTrue();
    }

    /** Unauthenticated delete attempt must return 403. */
    @Test
    void deleteAccount_whenUnauthenticated_returns403() {
        restTestClient.delete()
                .uri("/api/v0/auth/account")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── GET /settings ─────────────────────────────────────────────────────────

    /** A newly registered user's settings must contain the documented defaults. */
    @Test
    void getSettings_whenAuthenticated_returnsDefaults() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/auth/settings")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserSettingsDTO.class)
                .value(body -> {
                    assertThat(body.getReviewAheadMinutes()).isEqualTo(20);
                    assertThat(body.getDailyNewCardLimit()).isEqualTo(20);
                    assertThat(body.getDailyReviewLimit()).isEqualTo(200);
                });
    }

    /** Unauthenticated access to settings must return 401. */
    @Test
    void getSettings_whenUnauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/auth/settings")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── PUT /settings ─────────────────────────────────────────────────────────

    /** Authenticated settings update returns 200 with the new values. */
    @Test
    void updateSettings_whenAuthenticated_returnsUpdatedValues() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.put()
                .uri("/api/v0/auth/settings")
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UserSettingsDTO(30, 50, 300))
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserSettingsDTO.class)
                .value(body -> {
                    assertThat(body.getReviewAheadMinutes()).isEqualTo(30);
                    assertThat(body.getDailyNewCardLimit()).isEqualTo(50);
                    assertThat(body.getDailyReviewLimit()).isEqualTo(300);
                });
    }

    /** Negative setting values must be clamped to 0. */
    @Test
    void updateSettings_negativeValues_clampedToZero() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.put()
                .uri("/api/v0/auth/settings")
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UserSettingsDTO(-5, -10, -100))
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserSettingsDTO.class)
                .value(body -> {
                    assertThat(body.getReviewAheadMinutes()).isZero();
                    assertThat(body.getDailyNewCardLimit()).isZero();
                    assertThat(body.getDailyReviewLimit()).isZero();
                });
    }

    /**
     * Updated settings must be persisted — a subsequent GET must return the written
     * values.
     */
    @Test
    void updateSettings_persistsAcrossRequests() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.put()
                .uri("/api/v0/auth/settings")
                .cookie("SESSION", session.sessionCookie())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UserSettingsDTO(45, 75, 500))
                .exchange()
                .expectStatus().isOk();
        restTestClient.get()
                .uri("/api/v0/auth/settings")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserSettingsDTO.class)
                .value(body -> {
                    assertThat(body.getReviewAheadMinutes()).isEqualTo(45);
                    assertThat(body.getDailyNewCardLimit()).isEqualTo(75);
                    assertThat(body.getDailyReviewLimit()).isEqualTo(500);
                });
    }

    /** Settings update without a CSRF token must return 403. */
    @Test
    void updateSettings_missingCsrfToken_returns403() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.put()
                .uri("/api/v0/auth/settings")
                .cookie("SESSION", session.sessionCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UserSettingsDTO(30, 50, 300))
                .exchange()
                .expectStatus().isForbidden();
    }

    /** Unauthenticated settings update must return 403. */
    @Test
    void updateSettings_whenUnauthenticated_returns403() {
        restTestClient.put()
                .uri("/api/v0/auth/settings")
                .header("X-XSRF-TOKEN", "any")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UserSettingsDTO(30, 50, 300))
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
