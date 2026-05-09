package com.kogura.FSRS_Flashcard_App.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kogura.FSRS_Flashcard_App.dto.PresignedPostResponse;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserSettingsRepository;
import com.kogura.FSRS_Flashcard_App.service.MediaMetadataService;
import com.kogura.FSRS_Flashcard_App.service.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full-stack integration tests for {@link com.kogura.FSRS_Flashcard_App.controller.S3Controller}.
 *
 * <p>Every test starts from a clean database state. Authentication follows the real CSRF flow:
 * {@code GET /api/v0/auth/csrf} establishes an anonymous pre-session; {@code POST /api/v0/auth/signup}
 * consumes it and returns an authenticated session used for subsequent requests.
 *
 * <p>{@link S3Service} is replaced with a Mockito bean so no real AWS calls are made. Individual
 * tests configure mock return values via {@code when()} and use {@link ArgumentCaptor} to assert
 * what the controller passes to the service.
 *
 * <p>{@link MediaMetadataService} is also mocked to prevent real AWS client initialization from
 * the full application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureRestTestClient
@Testcontainers
class S3ControllerIT {

    /** Injected random port chosen by the embedded server at startup. */
    @LocalServerPort
    private int port;

    /**
     * Singleton PostgreSQL container shared across all tests in this JVM.
     * Started once to avoid per-test container overhead.
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
     * Replaces the real {@link S3Service} bean so presigned-upload and presigned-download
     * operations do not make real AWS S3 calls. Individual tests configure return values
     * via {@code when()} and verify call arguments via {@link ArgumentCaptor}.
     */
    @MockitoBean
    private S3Service s3Service;

    /**
     * Replaces the real {@link MediaMetadataService} bean to prevent real AWS client
     * initialization from the full application context.
     */
    @MockitoBean
    private MediaMetadataService mediaMetadataService;

    /** REST client rebuilt against the random port before every test. */
    @Autowired
    private RestTestClient restTestClient;

    /** Used to delete users during {@link #setUp()} to avoid FK violations. */
    @Autowired
    private UserRepository userRepository;

    /** Deleted during {@link #setUp()} before users to respect the FK constraint. */
    @Autowired
    private UserSettingsRepository userSettingsRepository;

    /** Used to issue direct SQL deletes in FK-safe order during {@link #setUp()}. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Jackson mapper for parsing response bodies in helper methods. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Setup ─────────────────────────────────────────────────────────────────

    /**
     * Wipes all application and session data before each test.
     * Deletion order respects FK constraints: session attributes before sessions,
     * user settings before users.
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
     * Builds a stub {@link PresignedPostResponse} used as the default mock return value for
     * {@code s3Service.createPresignedPostData()} in upload tests.
     */
    private PresignedPostResponse stubPresignedPost() {
        return new PresignedPostResponse(
                "https://test-bucket.s3.amazonaws.com",
                Map.of("key", "uploads/alice/uuid/photo.jpg", "policy", "encoded-policy"));
    }

    // ── GET /api/v0/s3/presigned-upload ───────────────────────────────────────

    /** Unauthenticated request must be rejected with 401 before the controller is reached. */
    @Test
    void presignedUpload_unauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=photo.jpg&isQuestion=true")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * A file name without a dot has no extension — must return 400 with a descriptive error.
     */
    @Test
    void presignedUpload_fileNameWithNoExtension_returns400() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=photo&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isBadRequest();
    }

    /**
     * An unsupported extension (e.g., {@code .pdf}) must return 400.
     * Only image and audio file types are permitted.
     */
    @Test
    void presignedUpload_unsupportedExtension_returns400() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=document.pdf&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isBadRequest();
    }

    /**
     * A plain-text file extension ({@code .txt}) must return 400.
     */
    @Test
    void presignedUpload_txtExtension_returns400() throws Exception {
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=notes.txt&isQuestion=false")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isBadRequest();
    }

    /**
     * A {@code .jpg} file is a supported image type — must return 200 with presigned data.
     */
    @Test
    void presignedUpload_jpgExtension_returns200() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=photo.jpg&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * A {@code .jpeg} file is a supported image type — must return 200 with presigned data.
     */
    @Test
    void presignedUpload_jpegExtension_returns200() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=photo.jpeg&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * A {@code .png} file is a supported image type — must return 200 with presigned data.
     */
    @Test
    void presignedUpload_pngExtension_returns200() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=image.png&isQuestion=false")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * A {@code .gif} file is a supported image type — must return 200 with presigned data.
     */
    @Test
    void presignedUpload_gifExtension_returns200() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=anim.gif&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * A {@code .webp} file is a supported image type — must return 200 with presigned data.
     */
    @Test
    void presignedUpload_webpExtension_returns200() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=image.webp&isQuestion=false")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * A {@code .mp3} file is a supported audio type — must return 200 with presigned data.
     */
    @Test
    void presignedUpload_mp3Extension_returns200() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=audio.mp3&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * A {@code .wav} file is a supported audio type — must return 200 with presigned data.
     */
    @Test
    void presignedUpload_wavExtension_returns200() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=audio.wav&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * A {@code .ogg} file is a supported audio type — must return 200 with presigned data.
     */
    @Test
    void presignedUpload_oggExtension_returns200() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=audio.ogg&isQuestion=false")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * The response body must contain a {@code url} string and a {@code fields} object,
     * both populated from the value returned by {@link S3Service#createPresignedPostData}.
     */
    @Test
    void presignedUpload_responseBodyContainsUrlAndFields() throws Exception {
        PresignedPostResponse stub = new PresignedPostResponse(
                "https://test-bucket.s3.amazonaws.com",
                Map.of("key", "uploads/alice/uuid/photo.jpg", "policy", "base64policy"));
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stub);

        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=photo.jpg&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.url").isEqualTo("https://test-bucket.s3.amazonaws.com")
                .jsonPath("$.fields.key").isEqualTo("uploads/alice/uuid/photo.jpg")
                .jsonPath("$.fields.policy").isEqualTo("base64policy");
    }

    /**
     * The S3 object key passed to {@link S3Service#createPresignedPostData} must begin with
     * {@code uploads/{username}/} using the authenticated user's username.
     */
    @Test
    void presignedUpload_s3KeyContainsAuthenticatedUsername() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=photo.jpg&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3Service).createPresignedPostData(keyCaptor.capture(), any(), any());
        assertThat(keyCaptor.getValue()).startsWith("uploads/alice/");
    }

    /**
     * The S3 object key must end with the original file name so that the object can be
     * identified by filename within the user's upload prefix.
     */
    @Test
    void presignedUpload_s3KeyEndsWithFileName() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=mycard.png&isQuestion=false")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3Service).createPresignedPostData(keyCaptor.capture(), any(), any());
        assertThat(keyCaptor.getValue()).endsWith("/mycard.png");
    }

    /**
     * The metadata map passed to {@link S3Service#createPresignedPostData} must contain
     * the {@code flashcardid} entry with the value from the {@code flashcardId} request param.
     */
    @Test
    @SuppressWarnings("unchecked")
    void presignedUpload_metadataContainsFlashcardId() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=42&fileName=photo.jpg&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<Map<String, String>> metaCaptor =
                ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        verify(s3Service).createPresignedPostData(any(), metaCaptor.capture(), any());
        assertThat(metaCaptor.getValue()).containsEntry("flashcardid", "42");
    }

    /**
     * When {@code isQuestion=true} the {@code isquestion} metadata entry passed to S3 must
     * be {@code "true"}, so the server can later validate the upload was intended for the
     * question side of the card.
     */
    @Test
    @SuppressWarnings("unchecked")
    void presignedUpload_metadataIsQuestion_trueWhenIsQuestionTrue() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=photo.jpg&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<Map<String, String>> metaCaptor =
                ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        verify(s3Service).createPresignedPostData(any(), metaCaptor.capture(), any());
        assertThat(metaCaptor.getValue()).containsEntry("isquestion", "true");
    }

    /**
     * When {@code isQuestion=false} the {@code isquestion} metadata entry must be
     * {@code "false"}, corresponding to the answer side of the card.
     */
    @Test
    @SuppressWarnings("unchecked")
    void presignedUpload_metadataIsQuestion_falseWhenIsQuestionFalse() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=photo.jpg&isQuestion=false")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<Map<String, String>> metaCaptor =
                ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        verify(s3Service).createPresignedPostData(any(), metaCaptor.capture(), any());
        assertThat(metaCaptor.getValue()).containsEntry("isquestion", "false");
    }

    /**
     * An image file extension must produce a {@code "image/"} content-type prefix when the
     * presigned POST policy is created, ensuring S3 enforces the correct MIME-type family.
     */
    @Test
    void presignedUpload_imageFile_passesImageContentTypePrefixToS3() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=photo.jpg&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<String> contentTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3Service).createPresignedPostData(any(), any(), contentTypeCaptor.capture());
        assertThat(contentTypeCaptor.getValue()).isEqualTo("image/");
    }

    /**
     * An audio file extension must produce an {@code "audio/"} content-type prefix so S3
     * enforces the correct MIME-type family for audio uploads.
     */
    @Test
    void presignedUpload_audioFile_passesAudioContentTypePrefixToS3() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=audio.mp3&isQuestion=false")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<String> contentTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3Service).createPresignedPostData(any(), any(), contentTypeCaptor.capture());
        assertThat(contentTypeCaptor.getValue()).isEqualTo("audio/");
    }

    /**
     * Extension comparison must be case-insensitive: {@code photo.JPG} must be accepted
     * as an image and return 200.
     */
    @Test
    void presignedUpload_extensionIsCaseInsensitive_returns200() throws Exception {
        when(s3Service.createPresignedPostData(any(), any(), any())).thenReturn(stubPresignedPost());
        UserSession session = signupUser("alice", "pass1");
        restTestClient.get()
                .uri("/api/v0/s3/presigned-upload?flashcardId=1&fileName=photo.JPG&isQuestion=true")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();
    }

    // ── GET /api/v0/s3/presigned-download ─────────────────────────────────────

    /** Unauthenticated request must be rejected with 401 before the controller is reached. */
    @Test
    void presignedDownload_unauthenticated_returns401() {
        restTestClient.get()
                .uri("/api/v0/s3/presigned-download?key=uploads/alice/uuid/photo.jpg")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * An authenticated request must return 200 with the URL string produced by
     * {@link S3Service#createPresignedDownloadUrl}.
     */
    @Test
    void presignedDownload_authenticated_returns200WithUrl() throws Exception {
        when(s3Service.createPresignedDownloadUrl(anyString()))
                .thenReturn("https://test-bucket.s3.amazonaws.com/presigned-download-url");
        UserSession session = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/s3/presigned-download?key=uploads/alice/uuid/photo.jpg")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo("https://test-bucket.s3.amazonaws.com/presigned-download-url");
    }

    /**
     * The {@code key} request parameter must be forwarded verbatim to
     * {@link S3Service#createPresignedDownloadUrl} so the service signs the correct object.
     */
    @Test
    void presignedDownload_forwardsKeyToS3Service() throws Exception {
        when(s3Service.createPresignedDownloadUrl(anyString())).thenReturn("https://example.com/url");
        UserSession session = signupUser("alice", "pass1");

        restTestClient.get()
                .uri("/api/v0/s3/presigned-download?key=uploads/alice/uuid/photo.jpg")
                .cookie("SESSION", session.sessionCookie())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3Service).createPresignedDownloadUrl(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("uploads/alice/uuid/photo.jpg");
    }
}
