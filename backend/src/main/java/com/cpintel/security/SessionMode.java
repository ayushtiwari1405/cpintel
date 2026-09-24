package com.cpintel.security;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Which kind of session the current request is: ordinary, or signed in for one examination.
 *
 * <p>An examination session comes from signing in with the examination password on a
 * candidate's slip rather than their account password. It can reach that one paper and nothing
 * else ({@link ExamModeFilter}), and a paper that has not ended can be reached by nothing but it.
 * The access token names the examination; {@link JwtAuthFilter} puts it on the request, and this
 * is how everything downstream asks.
 *
 * <p>Outside a request (a scheduler, the sync pool) there is no session and the answer is
 * "ordinary", which is right: none of that work acts for a candidate inside a paper.
 */
public final class SessionMode {

    private SessionMode() {}

    /** The request attribute {@link JwtAuthFilter} sets for an examination session. */
    public static final String EXAM_ATTRIBUTE = "cpintel.session.examId";

    /** The examination this session was signed in for, or null for an ordinary session. */
    public static Long examId() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        Object value = attrs.getAttribute(EXAM_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof Long id ? id : null;
    }

    public static boolean isExamSession() {
        return examId() != null;
    }

    /** True when this session was signed in for exactly this examination. */
    public static boolean isExamSessionFor(Long examId) {
        return examId != null && examId.equals(examId());
    }
}
