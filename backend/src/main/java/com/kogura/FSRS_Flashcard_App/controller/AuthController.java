package com.kogura.FSRS_Flashcard_App.controller;

import com.kogura.FSRS_Flashcard_App.dto.AuthResponse;
import com.kogura.FSRS_Flashcard_App.dto.LoginRequest;
import com.kogura.FSRS_Flashcard_App.dto.SignupRequest;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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

    @PostMapping("/logout")
    public ResponseEntity<AuthResponse> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(new AuthResponse("Logged out successfully", null));
    }

    @GetMapping("/csrf")
    public ResponseEntity<AuthResponse> csrf(CsrfToken csrfToken) {
        // Force the token to be generated and the cookie to be set
        csrfToken.getToken(); 
        return ResponseEntity.ok(new AuthResponse("CSRF token set", null));
    }

    @PostMapping("/test")
    public ResponseEntity<AuthResponse> test() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(401).body(new AuthResponse("Not authenticated", null));
        }
        return ResponseEntity.ok(new AuthResponse("Authenticated", authentication.getName()));
    }
}
