package com.kogura.FSRS_Flashcard_App.config;

import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Configuration for the security of the application.
 * Includes CSRF protection, session management, and authentication.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * Create a security filter chain.
     * @param http The HTTP security configuration.
     * @return The security filter chain.
     * @throws Exception If the security filter chain cannot be created.
     */
    @Bean
    public HttpSessionCsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repo = new HttpSessionCsrfTokenRepository();
        repo.setHeaderName("X-XSRF-TOKEN");
        return repo;
    }

    /**
     * Configures the application's security filter chain.
     *
     * <ul>
     *   <li><b>CORS</b> — delegates to {@link #corsConfigurationSource()}.</li>
     *   <li><b>CSRF</b> — uses {@link #csrfTokenRepository()} (cookie-less, header-based
     *       {@code X-XSRF-TOKEN}) with {@code CsrfTokenRequestAttributeHandler} so the raw
     *       token value is compared directly without double-submit-cookie encoding.</li>
     *   <li><b>Authorization</b> — {@code /api/v0/auth/csrf}, {@code /api/v0/auth/login},
     *       and {@code /api/v0/auth/signup} are public; all other requests require an
     *       authenticated session.</li>
     *   <li><b>Sessions</b> — created only when required; the security context is stored via
     *       {@link #securityContextRepository()} which never creates sessions on its own,
     *       preventing anonymous session proliferation.</li>
     *   <li><b>Request cache</b> — disabled ({@code NullRequestCache}) so unauthenticated
     *       requests are not saved and replayed after login.</li>
     *   <li><b>Auth errors</b> — returns {@code 401 Unauthorized} as JSON instead of
     *       redirecting to a login page.</li>
     *   <li><b>Logout</b> — disabled; logout is handled by
     *       {@code AuthController.logout()}.</li>
     * </ul>
     *
     * @param http the {@link HttpSecurity} to configure
     * @return the built {@link SecurityFilterChain}
     * @throws Exception if any configuration step fails
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf((csrf) -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(auth -> auth
                        // Allow these endpoints to be used without authenticated session cookies 
                        // (pre-sessions + CSRF token needed for login and signup)
                        .requestMatchers("/api/v0/auth/csrf", "/api/v0/auth/login", "/api/v0/auth/signup").permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository())
                )
                // Disable Session Creation on Unauthorized Requests 
                .requestCache(cache -> cache
                    .requestCache(new NullRequestCache())
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType("application/json");
                            response.getWriter().write("{\"message\":\"Unauthorized\",\"username\":null}");
                        })
                )
                .logout(logout -> logout.disable());

        return http.build();
    }

    /**
     * Create a security context repository.
     * This repository is used to store the security context in the session.
     * @return The security context repository.
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();
        repo.setAllowSessionCreation(false);
        return repo;
    }

    /**
     * Create a password encoder.
     * This encoder is used to encode the password of the user.
     * @return The password encoder.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Create an authentication manager.
     * @param config The authentication configuration.
     * @return The authentication manager.
     * @throws Exception If the authentication manager cannot be created.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

  /**
   * Create a CORS configuration source.
   * This source is used to configure the CORS of the application.
   * @return The CORS configuration source.
   */
  @Bean
  public UrlBasedCorsConfigurationSource corsConfigurationSource() {
      CorsConfiguration config = new CorsConfiguration();
      // TOOD: Change to use actual frontend source 
      config.setAllowedOrigins(List.of("http://localhost:5173"));
      config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
      config.setAllowedHeaders(List.of("*"));
      config.setAllowCredentials(true);

      UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
      source.registerCorsConfiguration("/**", config);
      return source;
  }
}
