package com.cpintel.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request an id, so its log lines can be found together.
 *
 * <p>Production logs as structured JSON, which was the right call and still left one request's
 * lines indistinguishable from another's. With async syncs, five scheduled jobs and ordinary
 * traffic all interleaving in one stream, reconstructing what happened to one user meant reading
 * timestamps and guessing. Now every line carries the same {@code requestId}, and
 * {@link MdcTaskDecorator} carries it onto the sync executor so the background half of an
 * operation is still attached to the request that started it.
 *
 * <p>An inbound {@code X-Request-Id} is honoured when present so a trace survives the hop from
 * nginx or the desktop client, and the id is echoed back on the response — which is what makes
 * it possible for someone reporting a problem to quote the identifier that finds it.
 *
 * <p>Ordered first: an id assigned after authentication would be missing from exactly the lines
 * that record why authentication failed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    public static final String HEADER  = "X-Request-Id";

    /** Long enough to be unique in a log, short enough to quote over the phone. */
    private static final int ID_LENGTH = 12;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (!StringUtils.hasText(id) || id.length() > 64) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, ID_LENGTH);
        }

        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);

        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled and reused. Left in place, this id would leak onto whatever
            // request the container handed this thread next.
            MDC.remove(MDC_KEY);
        }
    }
}
