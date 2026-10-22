package com.cpintel.web;

import com.cpintel.classrooms.ClassroomAccessInterceptor;
import com.cpintel.config.AppMetrics;
import com.cpintel.config.CorsConfig;
import com.cpintel.config.SecurityConfig;
import com.cpintel.controller.UserController;
import com.cpintel.security.JwtAuthFilter;
import com.cpintel.security.JwtProperties;
import com.cpintel.security.JwtService;
import com.cpintel.security.RateLimitFilter;
import com.cpintel.security.RateLimitProperties;
import com.cpintel.security.RateLimitService;
import com.cpintel.service.UserService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The bearer token on a request, through the real JwtAuthFilter, JwtService and SecurityConfig.
 *
 * <p>The authorisation tests install an authentication directly and never parse a token; these
 * are the other half - which tokens are believed at all. Every way a token can be wrong ends in
 * the same 401, and the controller behind it is never reached. Redis, which holds the sign-out
 * list and the per-user revocations, is a mock.
 */
@WebMvcTest(controllers = UserController.class)
@Import({ SecurityConfig.class, CorsConfig.class, JwtAuthFilter.class, RateLimitFilter.class, JwtService.class,
    JwtProperties.class })
@EnableConfigurationProperties
// JwtProperties is bound from jwt.*, so the key for these tests is given here. A test-only value.
@TestPropertySource(properties = { "jwt.secret=" + BearerTokenTest.SECRET, "jwt.expiry-ms=900000",
    "jwt.refresh-expiry-ms=604800000" })
class BearerTokenTest {

    static final String SECRET = "bearer-token-test-key-not-used-anywhere-else-0001";
    private static final long USER_ID = 42L;

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;

    @MockBean private UserService userService;
    @MockBean private RedisTemplate<String, Object> redis;
    @MockBean private RateLimitService rateLimitService;
    @MockBean private RateLimitProperties rateLimitProperties;
    @MockBean private AppMetrics appMetrics;
    @MockBean private ClassroomAccessInterceptor classroomAccess;

    private ValueOperations<String, Object> values;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.hasKey(anyString())).thenReturn(false);
        when(classroomAccess.preHandle(any(), any(), any())).thenReturn(true);
        when(rateLimitService.tryAcquire(anyString(), any(), any())).thenReturn(true);
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule(1000, java.time.Duration.ofMinutes(1));
        when(rateLimitProperties.getLogin()).thenReturn(rule);
        when(rateLimitProperties.getRecovery()).thenReturn(rule);
        when(rateLimitProperties.getRun()).thenReturn(rule);
        when(rateLimitProperties.getSync()).thenReturn(rule);
    }

    /** A token as JwtService signs one, with the issue and expiry times given. */
    private static String token(String secret, Date issued, Date expires) {
        return Jwts.builder().id(UUID.randomUUID().toString()).subject(String.valueOf(USER_ID))
            .claim("role", "USER").claim("email", "someone@example.edu")
            .issuedAt(issued).expiration(expires)
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)),
                Jwts.SIG.HS256)
            .compact();
    }

    private void refused(String authorization) throws Exception {
        var request = get("/api/v1/users/me");
        if (authorization != null) request = request.header("Authorization", authorization);
        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("a token JwtService issued is accepted, as the user it names")
    void validToken() throws Exception {
        mvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + jwt.generateAccessToken(USER_ID, "someone@example.edu", "USER")))
            .andExpect(status().isOk());
        verify(userService).getProfile(USER_ID);
    }

    @Test
    @DisplayName("no token, another scheme, or a token that is not a JWT: 401, and the controller is not reached")
    void missingOrGarbage() throws Exception {
        refused(null);
        refused("Bearer");
        refused("Bearer ");
        refused("Bearer not-a-jwt");
        refused("Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8)));
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("a token signed with another key is refused")
    void wrongKey() throws Exception {
        long now = System.currentTimeMillis();
        refused("Bearer " + token("another-key-entirely-" + UUID.randomUUID(), new Date(now), new Date(now + 60_000)));
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("an expired token is refused")
    void expired() throws Exception {
        long now = System.currentTimeMillis();
        refused("Bearer " + token(SECRET, new Date(now - 3_600_000), new Date(now - 1_000)));
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("an unsigned token (alg none) is refused, whatever it claims")
    void unsigned() throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String body = b64.encodeToString(("{\"sub\":\"" + USER_ID + "\",\"role\":\"SUPER_ADMIN\",\"exp\":"
            + (System.currentTimeMillis() / 1000 + 600) + "}").getBytes(StandardCharsets.UTF_8));
        refused("Bearer " + header + "." + body + ".");
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("a token whose payload was edited after signing is refused")
    void tampered() throws Exception {
        String[] parts = jwt.generateAccessToken(USER_ID, "someone@example.edu", "USER").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            .replace("\"USER\"", "\"SUPER_ADMIN\"");
        String edited = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        refused("Bearer " + parts[0] + "." + edited + "." + parts[2]);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("a token that was signed out is refused")
    void signedOut() throws Exception {
        String token = jwt.generateAccessToken(USER_ID, "someone@example.edu", "USER");
        when(redis.hasKey(startsWith("jwt:blacklist:"))).thenReturn(true);
        refused("Bearer " + token);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("a token issued before the account's tokens were revoked is refused")
    void revokedForUser() throws Exception {
        long now = System.currentTimeMillis();
        String token = token(SECRET, new Date(now - 120_000), new Date(now + 600_000));
        when(values.get("jwt:user-revoked:" + USER_ID)).thenReturn(String.valueOf(now - 10_000));
        refused("Bearer " + token);
        verifyNoInteractions(userService);
    }
}
