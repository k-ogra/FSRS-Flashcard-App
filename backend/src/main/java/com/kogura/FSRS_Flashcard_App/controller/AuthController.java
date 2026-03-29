package com.kogura.FSRS_Flashcard_App.controller;

import com.kogura.FSRS_Flashcard_App.dto.AuthResponse;
import com.kogura.FSRS_Flashcard_App.dto.LoginRequest;
import com.kogura.FSRS_Flashcard_App.dto.SignupRequest;
import com.kogura.FSRS_Flashcard_App.dto.UserSettingsDTO;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.model.UserSettings;
import com.kogura.FSRS_Flashcard_App.repository.DeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.SharedDeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserSettingsRepository;
import com.kogura.FSRS_Flashcard_App.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

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

    /*
     * Sign up a new user.
     * @param request The signup request.
     * @param httpRequest The HTTP request.
     * @return The authentication response.
     */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@RequestBody SignupRequest request,
                                               HttpServletRequest httpRequest) {
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

        // Persist the security context in the session
        SecurityContext context = SecurityContextHolder.getContext();
        context.setAuthentication(authentication);
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );

        return ResponseEntity.ok(new AuthResponse("Signup successful", user.getUsername()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        Authentication authentication = authService.login(request);

        // Persist the security context in the session
        SecurityContext context = SecurityContextHolder.getContext();
        context.setAuthentication(authentication);
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );

        return ResponseEntity.ok(new AuthResponse("Login successful", request.getUsername()));
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
     * @param csrfToken The CSRF token.
     * @return The authentication response.
     */
    @GetMapping("/csrf")
    public ResponseEntity<AuthResponse> csrf(CsrfToken csrfToken) {
        // Force the token to be generated and the cookie to be set
        csrfToken.getToken(); 
        return ResponseEntity.ok(new AuthResponse("CSRF token set", null));
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
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(401).body(new AuthResponse("Unauthorized", null));
        }

        String username = authentication.getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Remove user settings
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
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(401).body(new AuthResponse("Unauthorized", null));
        }
        return ResponseEntity.ok(new AuthResponse("Authenticated", authentication.getName()));
    }

    /**
     * Get the user settings.
     * @return The user settings.
     */
    @GetMapping("/settings")
    public ResponseEntity<UserSettingsDTO> getSettings() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(401).build();
        }
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        UserSettings settings = userSettingsRepository.findByUser(user)
                .orElseGet(() -> {
                    UserSettings s = new UserSettings();
                    s.setUser(user);
                    return userSettingsRepository.save(s);
                });
        return ResponseEntity.ok(new UserSettingsDTO(settings.getReviewAheadMinutes()));
    }

    /**
     * Update the user settings.
     * @param request The user settings request.
     * @return The updated user settings.
     */
    @PutMapping("/settings")
    public ResponseEntity<UserSettingsDTO> updateSettings(@RequestBody UserSettingsDTO request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(401).build();
        }
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        UserSettings settings = userSettingsRepository.findByUser(user)
                .orElseGet(() -> {
                    UserSettings s = new UserSettings();
                    s.setUser(user);
                    return s;
                });
        settings.setReviewAheadMinutes(Math.max(0, request.getReviewAheadMinutes()));
        userSettingsRepository.save(settings);
        return ResponseEntity.ok(new UserSettingsDTO(settings.getReviewAheadMinutes()));
    }
}
