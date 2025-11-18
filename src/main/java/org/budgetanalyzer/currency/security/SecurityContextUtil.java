package org.budgetanalyzer.currency.security;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Utility class for extracting user information from the Spring Security context.
 *
 * <p>This utility extracts user identity from JWT tokens validated by Spring Security OAuth2
 * Resource Server. Currently used for logging and audit purposes.
 *
 * <p>Note: Exchange rate data is generally public (not user-specific), so data-level authorization
 * may not be needed. However, we log user context for audit and compliance purposes.
 */
public class SecurityContextUtil {

  private static final Logger logger = LoggerFactory.getLogger(SecurityContextUtil.class);

  private SecurityContextUtil() {
    // Utility class - prevent instantiation
  }

  /**
   * Extracts the user ID from the current security context.
   *
   * <p>The user ID is extracted from the JWT 'sub' (subject) claim, which contains the Auth0 user
   * identifier (e.g., "auth0|123456789").
   *
   * @return Optional containing the user ID if authenticated, empty otherwise
   */
  public static Optional<String> getCurrentUserId() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication instanceof JwtAuthenticationToken jwtAuth) {
      var jwt = jwtAuth.getToken();
      var userId = jwt.getSubject(); // Extract 'sub' claim
      logger.trace("Extracted user ID from JWT: {}", userId);
      return Optional.ofNullable(userId);
    }

    logger.trace("No JWT authentication found in security context");
    return Optional.empty();
  }

  /**
   * Extracts the user's email from the current security context.
   *
   * <p>The email is extracted from the JWT 'email' claim if present.
   *
   * @return Optional containing the user email if present, empty otherwise
   */
  public static Optional<String> getCurrentUserEmail() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication instanceof JwtAuthenticationToken jwtAuth) {
      var jwt = jwtAuth.getToken();
      var email = jwt.getClaimAsString("email");
      logger.trace("Extracted user email from JWT: {}", email);
      return Optional.ofNullable(email);
    }

    return Optional.empty();
  }

  /**
   * Extracts all JWT claims from the current security context.
   *
   * <p>Useful for debugging and understanding what claims are available in the JWT.
   *
   * @return Optional containing all JWT claims if authenticated, empty otherwise
   */
  public static Optional<Map<String, Object>> getAllClaims() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication instanceof JwtAuthenticationToken jwtAuth) {
      var jwt = jwtAuth.getToken();
      var claims = jwt.getClaims();

      logger.trace("JWT claims: {}", claims);
      return Optional.of(claims);
    }

    return Optional.empty();
  }

  /**
   * Logs user authentication context for audit purposes.
   *
   * <p>Logs the user ID, email, and granted authorities from the JWT. This provides visibility into
   * who is making API requests.
   */
  public static void logAuthenticationContext() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication instanceof JwtAuthenticationToken jwtAuth) {
      var jwt = jwtAuth.getToken();
      var userId = jwt.getSubject();
      var email = jwt.getClaimAsString("email");
      var authorities = authentication.getAuthorities();

      logger.info(
          "Authenticated user - ID: {}, Email: {}, Authorities: {}", userId, email, authorities);
    } else if (authentication != null) {
      logger.info("Authenticated principal (non-JWT): {}", authentication.getName());
    } else {
      logger.debug("No authentication in security context");
    }
  }
}
