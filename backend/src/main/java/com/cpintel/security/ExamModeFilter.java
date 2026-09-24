package com.cpintel.security;

import com.cpintel.entity.GroupContest;
import com.cpintel.repository.jpa.GroupContestRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds an examination session to its one paper.
 *
 * <p>A candidate who signed in with the examination password on their slip gets a session that
 * can do exactly what sitting that paper needs — read and submit to its judge contest, run code
 * against it, report the monitor, read their own profile — and nothing else: no practice, no
 * other contests, no settings, no files outside the paper's own rule, no admin console. An
 * allow-list rather than a deny-list, so a route added next month is closed to examination
 * sessions until somebody decides it belongs to sitting a paper.
 *
 * <p>Runs after the security chain, which is where {@link JwtAuthFilter} marks the request.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExamModeFilter extends OncePerRequestFilter {

    private static final Pattern EXAM_ROUTE = Pattern.compile("^/exams/(\\d+)(/.*)?$");
    private static final Pattern COMPETE_ROUTE = Pattern.compile("^/compete/([A-Za-z]+)/([^/]+)(/.*)?$");

    /**
     * Looked up only for an examination session's compete route, and lazily: a filter is built
     * into every web context, including slices that have no JPA at all.
     */
    private final org.springframework.beans.factory.ObjectProvider<GroupContestRepository> events;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Object attribute = request.getAttribute(SessionMode.EXAM_ATTRIBUTE);
        if (!(attribute instanceof Long examId) || allowed(request, examId)) {
            chain.doFilter(request, response);
            return;
        }
        log.info("Refused {} {} to an examination session for exam {}",
            request.getMethod(), request.getRequestURI(), examId);
        response.setStatus(403);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"EXAM_MODE\",\"message\":\"Not available while "
            + "you are signed in for an examination. Sign out and sign in with your account "
            + "password for everything else.\"}");
    }

    private boolean allowed(HttpServletRequest request, long examId) {
        String path = route(request);
        String method = request.getMethod();
        boolean read = "GET".equals(method);

        // Signing out and renewing; who am I.
        if (path.equals("/auth/logout") || path.equals("/auth/refresh")) return true;
        if (path.equals("/users/me")) return read;

        // The paper itself, and the list the workspace starts from.
        if (path.equals("/exams")) return read;
        Matcher exam = EXAM_ROUTE.matcher(path);
        if (exam.matches()) return Long.parseLong(exam.group(1)) == examId;

        // Its judge contest, and nothing on any other.
        Matcher compete = COMPETE_ROUTE.matcher(path);
        if (compete.matches()) {
            GroupContest paper = events.getObject().findById(examId).orElse(null);
            return paper != null
                && paper.getPlatform().equalsIgnoreCase(compete.group(1))
                && paper.getExternalId().equals(compete.group(2));
        }

        // Running code — RunController holds a run to this paper's contest and languages —
        // and reading back what was submitted into it (scoped by LiveExamGuard).
        if (path.equals("/run") || path.startsWith("/run/")) return true;
        if (path.startsWith("/submissions/")) return read;

        // Read-only status the workspace asks for on the way in.
        if (path.equals("/groups/contests/active") || path.equals("/practice/cf-session")) {
            return read;
        }
        return false;
    }

    /** The path with the context, "/api" and the version segment taken off: "/exams/7". */
    private static String route(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        if (!path.startsWith("/api/")) return path;
        path = path.substring(4);
        int next = path.indexOf('/', 1);
        String head = next < 0 ? path.substring(1) : path.substring(1, next);
        if (head.length() > 1 && head.toLowerCase(Locale.ROOT).charAt(0) == 'v'
                && head.chars().skip(1).allMatch(Character::isDigit)) {
            path = next < 0 ? "/" : path.substring(next);
        }
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }
}
