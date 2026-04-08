package com.kogura.FSRS_Flashcard_App.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.kogura.FSRS_Flashcard_App.dto.LoginRequest;
import com.kogura.FSRS_Flashcard_App.dto.SignupRequest;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {

  /** Mock for user persistence operations. */
  @Mock
  private UserRepository userRepository;

  /** Mock for password hashing. */
  @Mock
  private PasswordEncoder passwordEncoder;

  /** Mock for Spring Security's authentication flow. */
  @Mock
  private AuthenticationManager authenticationManager;

  /** The service under test. */
  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService = new AuthService(userRepository, passwordEncoder, authenticationManager);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  // ── Helper methods ─────────────────────────────────────────

  private SignupRequest signupRequest(String username, String password) {
    SignupRequest req = new SignupRequest();
    req.setUsername(username);
    req.setPassword(password);
    return req;
  }

  private LoginRequest loginRequest(String username, String password) {
    LoginRequest req = new LoginRequest();
    req.setUsername(username);
    req.setPassword(password);
    return req;
  }

  // ── signup ─────────────────────────────────────────────────

  /**
   * Verifies that a successful signup encodes the password, persists a new {@link User},
   * and returns the saved entity.
   */
  @Test
  void signup_success_encodesPasswordAndSavesUser() {
    SignupRequest request = signupRequest("newuser", "plaintext");

    when(userRepository.existsByUsername("newuser")).thenReturn(false);
    when(passwordEncoder.encode("plaintext")).thenReturn("hashed-password");
    when(userRepository.save(any(User.class))).thenAnswer(inv -> {
      User u = inv.getArgument(0);
      u.setId(1L);
      return u;
    });

    User result = authService.signup(request);

    assertThat(result.getUsername()).isEqualTo("newuser");
    assertThat(result.getPassword()).isEqualTo("hashed-password");
    assertThat(result.getId()).isEqualTo(1L);
  }

  /**
   * Verifies that the user passed to the repository has the correct username and the
   * encoded (not plaintext) password.
   */
  @Test
  void signup_success_passesCorrectUserToRepository() {
    SignupRequest request = signupRequest("alice", "secret123");

    when(userRepository.existsByUsername("alice")).thenReturn(false);
    when(passwordEncoder.encode("secret123")).thenReturn("encoded-secret");
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    authService.signup(request);

    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    User saved = captor.getValue();
    assertThat(saved.getUsername()).isEqualTo("alice");
    assertThat(saved.getPassword()).isEqualTo("encoded-secret");
  }

  /**
   * Verifies that signing up with an already-taken username throws an
   * {@link IllegalArgumentException} with a generic error message.
   */
  @Test
  void signup_duplicateUsername_throwsIllegalArgumentException() {
    SignupRequest request = signupRequest("existing", "password");

    when(userRepository.existsByUsername("existing")).thenReturn(true);

    assertThatThrownBy(() -> authService.signup(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Bad signup request");
  }

  /**
   * Verifies that when a duplicate username is detected, no user is saved and the
   * password encoder is never called.
   */
  @Test
  void signup_duplicateUsername_doesNotSaveOrEncode() {
    SignupRequest request = signupRequest("existing", "password");

    when(userRepository.existsByUsername("existing")).thenReturn(true);

    try {
      authService.signup(request);
    } catch (IllegalArgumentException ignored) {
    }

    verify(userRepository, never()).save(any(User.class));
    verify(passwordEncoder, never()).encode(any());
  }

  // ── login ──────────────────────────────────────────────────

  /**
   * Verifies that a successful login delegates to the {@link AuthenticationManager}
   * with a {@link UsernamePasswordAuthenticationToken} containing the correct credentials
   * and returns the resulting {@link Authentication}.
   */
  @Test
  void login_success_authenticatesAndReturnsAuthentication() {
    LoginRequest request = loginRequest("user1", "pass1");
    Authentication expectedAuth = new UsernamePasswordAuthenticationToken("user1", "pass1");

    when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
        .thenReturn(expectedAuth);

    Authentication result = authService.login(request);

    assertThat(result).isEqualTo(expectedAuth);
  }

  /**
   * Verifies that the {@link AuthenticationManager} receives a token with the
   * correct username and password from the login request.
   */
  @Test
  void login_success_passesCorrectCredentialsToAuthManager() {
    LoginRequest request = loginRequest("bob", "bobpass");
    Authentication auth = new UsernamePasswordAuthenticationToken("bob", "bobpass");

    when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
        .thenReturn(auth);

    authService.login(request);

    verify(authenticationManager).authenticate(
        argThat(token -> token.getPrincipal().equals("bob")
            && token.getCredentials().equals("bobpass")));
  }

  /**
   * Verifies that after a successful login, the {@link SecurityContextHolder} contains
   * the returned authentication object.
   */
  @Test
  void login_success_setsSecurityContext() {
    LoginRequest request = loginRequest("user1", "pass1");
    Authentication expectedAuth = new UsernamePasswordAuthenticationToken("user1", "pass1");

    when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
        .thenReturn(expectedAuth);

    authService.login(request);

    Authentication contextAuth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(contextAuth).isEqualTo(expectedAuth);
  }

  /**
   * Verifies that when the {@link AuthenticationManager} rejects credentials (e.g. wrong
   * password), the {@link BadCredentialsException} propagates to the caller.
   */
  @Test
  void login_badCredentials_throwsBadCredentialsException() {
    LoginRequest request = loginRequest("user1", "wrong");

    when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
        .thenThrow(new BadCredentialsException("Bad credentials"));

    assertThatThrownBy(() -> authService.login(request))
        .isInstanceOf(BadCredentialsException.class);
  }
}
