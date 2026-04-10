package com.kogura.FSRS_Flashcard_App.service;

import com.kogura.FSRS_Flashcard_App.dto.LoginRequest;
import com.kogura.FSRS_Flashcard_App.dto.SignupRequest;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Handles user registration and authentication — encodes passwords for storage
 * and delegates credential validation to Spring Security's {@link AuthenticationManager}.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Repository for user persistence and username-existence checks. */
    private final UserRepository userRepository;

    /** Encoder used to hash plaintext passwords before storage. */
    private final PasswordEncoder passwordEncoder;

    /** Spring Security manager that validates credentials during login. */
    private final AuthenticationManager authenticationManager;

    /**
     * Registers a new user. The password is hashed before persistence. Throws if the
     * username is already taken, using a generic error message to avoid leaking which
     * usernames exist.
     *
     * @param request the signup payload containing username and plaintext password
     * @return the persisted {@link User} entity
     * @throws IllegalArgumentException if the username is already in use
     */
    public User signup(SignupRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            // Keep error messages generic for security
            throw new IllegalArgumentException("Bad signup request");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        return userRepository.save(user);
    }

    /**
     * Authenticates a user with the provided credentials. On success, stores the
     * resulting {@link Authentication} in the {@link SecurityContextHolder} so that
     * downstream filters and handlers can access the authenticated principal.
     *
     * @param request the login payload containing username and password
     * @return the authenticated {@link Authentication} token
     * @throws org.springframework.security.authentication.BadCredentialsException
     *         if the credentials are invalid
     */
    public Authentication login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }
}
