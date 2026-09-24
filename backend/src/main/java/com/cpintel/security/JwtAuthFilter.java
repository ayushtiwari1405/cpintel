package com.cpintel.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);

        if (token != null && jwtService.isValid(token)) {
            try {
                var claims = jwtService.extractClaims(token);
                Long userId = Long.parseLong(claims.getSubject());
                String role = (String) claims.get("role");

                // An admin who deactivates an account, or changes its role, expects that to
                // take hold now rather than whenever the token in that browser happens to
                // expire. A token older than the revocation is treated as absent, which sends
                // the client through refresh — where the account state is checked properly.
                if (!jwtService.isRevokedForUser(userId, claims.getIssuedAt())) {
                    // An examination session reaches one paper and nothing else; the request
                    // carries which, and ExamModeFilter holds it to that.
                    Object exam = claims.get(JwtService.EXAM_CLAIM);
                    if (exam instanceof Number examId) {
                        request.setAttribute(SessionMode.EXAM_ATTRIBUTE, examId.longValue());
                    }
                    var auth = new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception e) {
                log.debug("JWT processing failed: {}", e.getMessage());
            }
        }

        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
