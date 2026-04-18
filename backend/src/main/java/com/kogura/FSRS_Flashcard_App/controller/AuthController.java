package com.kogura.FSRS_Flashcard_App.controller;

import com.kogura.FSRS_Flashcard_App.dto.AuthResponse;
import com.kogura.FSRS_Flashcard_App.dto.LoginRequest;
import com.kogura.FSRS_Flashcard_App.dto.SignupRequest;
import com.kogura.FSRS_Flashcard_App.dto.UserSettingsDTO;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.model.UserSettings;
import com.kogura.FSRS_Flashcard_App.repository.DailyStudyProgressRepository;
import com.kogura.FSRS_Flashcard_App.repository.DeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.SharedDeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserSettingsRepository;
import com.kogura.FSRS_Flashcard_App.service.AuthService;
import com.kogura.FSRS_Flashcard_App.service.S3Service;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for the authentication endpoints.
 */
@RestController
@RequestMapping("/api/v0/auth")
@RequiredArgsConstructor
public class AuthController {

    /**
     * The authentication service.
     */
    private final AuthService authService;
    /**
     * The user repository.
     */
    private final UserRepository userRepository;
    /**
     * The deck repository.
     */
    private final DeckRepository deckRepository;
    /**
     * The shared deck repository.
     */
    private final SharedDeckRepository sharedDeckRepository;
    /**
     * The user settings repository.
     */
    private final UserSettingsRepository userSettingsRepository;
    /**
     * The daily study progress repository.
     */
    private final DailyStudyProgressRepository dailyStudyProgressRepository;
    /**
     * The S3 service used to clean up a user's uploaded media on account deletion.
     */
    private final S3Service s3Service;
    /**
     * The CSRF token repository, used to generate and save session-tied tokens after login/signup.
     */
    private final CsrfTokenRepository csrfTokenRepository;

    /**
     * Sign up a new user.
     * @param request The signup request.
     * @param httpRequest The HTTP request.
     * @param httpResponse The HTTP response.
     * @return The authentication response.
     */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@RequestBody SignupRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        User user;
        try {
            user = authService.signup(request);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new AuthResponse(e.getMessage(), null));
        }

        // Auto-login after signup by authenticating with the provided credentials
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(request.getUsername());
        loginRequest.setPassword(request.getPassword());
        Authentication authentication = authService.login(loginRequest);

        // Session fixation protection: invalidate the pre-session and start a fresh authenticated session
        HttpSession oldSession = httpRequest.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        SecurityContext context = SecurityContextHolder.getContext();
        context.setAuthentication(authentication);
        HttpSession newSession = httpRequest.getSession(true);
        newSession.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );

        // Generate a new session-tied CSRF token and return it in the response header
        CsrfToken sessionCsrf = csrfTokenRepository.generateToken(httpRequest);
        csrfTokenRepository.saveToken(sessionCsrf, httpRequest, httpResponse);

        return ResponseEntity.ok()
                .header("X-CSRF-TOKEN", sessionCsrf.getToken())
                .body(new AuthResponse("Signup successful", user.getUsername()));
    }

    /**
     * Log in a user.
     * @param request The login request.
     * @param httpRequest The HTTP request.
     * @param httpResponse The HTTP response.
     * @return The authentication response.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        Authentication authentication = authService.login(request);

        // Session fixation protection: invalidate the pre-session and start a fresh authenticated session
        HttpSession oldSession = httpRequest.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        SecurityContext context = SecurityContextHolder.getContext();
        context.setAuthentication(authentication);
        HttpSession newSession = httpRequest.getSession(true);
        newSession.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );

        // Generate a new session-tied CSRF token and return it in the response header
        CsrfToken sessionCsrf = csrfTokenRepository.generateToken(httpRequest);
        csrfTokenRepository.saveToken(sessionCsrf, httpRequest, httpResponse);

        return ResponseEntity.ok()
                .header("X-CSRF-TOKEN", sessionCsrf.getToken())
                .body(new AuthResponse("Login successful", request.getUsername()));
    }

    /**
     * Log out a user.
     * @param request The HTTP request.
     * @return The authentication response.
     */
    @PostMapping("/logout")
    public ResponseEntity<AuthResponse> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(new AuthResponse("Logged out successfully", null));
    }

    /**
     * Get the CSRF token.
     * @param csrfToken The CSRF token
     * @return The CSRF token response.
     */
    @GetMapping("/csrf")
    public CsrfToken csrf(HttpServletRequest request, CsrfToken csrfToken) {
        // Force session creation before the response is committed so that
        // Spring Session can set the SESSION cookie in the response headers.
        // Without this, the session would only be created lazily during
        // Jackson serialization of the CsrfToken body, at which point the
        // cookie can no longer be reliably written to the response.
        request.getSession(true);
        return csrfToken;
    }

    /**
     * Delete a user's account.
     * @param request The HTTP request.
     * @return The authentication response.
     */
    @DeleteMapping("/account")
    @Transactional
    public ResponseEntity<AuthResponse> deleteAccount(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        String username = authentication.getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Delete the user's uploaded media from S3 first. If this throws, the @Transactional
        // rollback leaves the account intact so the user can retry
        s3Service.deleteObjectsByPrefix("uploads/" + username + "/");

        // Remove daily study progress and user settings
        dailyStudyProgressRepository.deleteByUser(user);
        userSettingsRepository.deleteByUser(user);

        // Remove shared deck records where user is recipient or sharer
        sharedDeckRepository.deleteByUser(user);
        sharedDeckRepository.deleteBySharer(user);

        // Remove all decks owned by the user (cascades to flashcards)
        deckRepository.deleteAll(deckRepository.findByUser(user));

        // Delete the user
        userRepository.delete(user);

        // Invalidate session
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();

        return ResponseEntity.ok(new AuthResponse("Account deleted successfully", null));
    }

    /**
     * Check if the user is authenticated.
     * @return The authentication response if the user is authenticated, otherwise return an unauthorized response.
     */
    @GetMapping("/authenticated")
    public ResponseEntity<AuthResponse> authenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(new AuthResponse("Authenticated", authentication.getName()));
    }

    /**
     * Get the user settings.
     * @return The user settings.
     */
    @GetMapping("/settings")
    public ResponseEntity<UserSettingsDTO> getSettings() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        UserSettings settings = userSettingsRepository.findByUser(user)
                .orElseGet(() -> {
                    UserSettings s = new UserSettings();
                    s.setUser(user);
                    return userSettingsRepository.save(s);
                });
        return ResponseEntity.ok(new UserSettingsDTO(
                settings.getReviewAheadMinutes(),
                settings.getDailyNewCardLimit(),
                settings.getDailyReviewLimit()));
    }

    /**
     * Update the user settings.
     * @param request The user settings request.
     * @return The updated user settings.
     */
    @PutMapping("/settings")
    public ResponseEntity<UserSettingsDTO> updateSettings(@RequestBody UserSettingsDTO request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();        
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        UserSettings settings = userSettingsRepository.findByUser(user)
                .orElseGet(() -> {
                    UserSettings s = new UserSettings();
                    s.setUser(user);
                    return s;
                });
        settings.setReviewAheadMinutes(Math.max(0, request.getReviewAheadMinutes()));
        settings.setDailyNewCardLimit(Math.max(0, request.getDailyNewCardLimit()));
        settings.setDailyReviewLimit(Math.max(0, request.getDailyReviewLimit()));
        userSettingsRepository.save(settings);
        return ResponseEntity.ok(new UserSettingsDTO(
                settings.getReviewAheadMinutes(),
                settings.getDailyNewCardLimit(),
                settings.getDailyReviewLimit()));
    }
}
