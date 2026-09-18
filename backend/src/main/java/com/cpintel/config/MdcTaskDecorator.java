package com.cpintel.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Carries the logging context from the request thread onto a pooled worker.
 *
 * <p>MDC is thread-local, so without this a job handed to {@code syncTaskExecutor} started with
 * an empty context and its log lines lost the request id of whatever asked for the sync — which
 * is precisely the case where correlation is worth having, since the work outlives the request
 * that triggered it by minutes.
 *
 * <p>The context is snapshotted at submission time and cleared afterwards, because the worker
 * thread goes back into the pool and would otherwise carry one request's id into the next job.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        Map<String, String> submitterContext = MDC.getCopyOfContextMap();

        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (submitterContext != null) MDC.setContextMap(submitterContext);
            try {
                task.run();
            } finally {
                if (previous != null) MDC.setContextMap(previous); else MDC.clear();
            }
        };
    }
}
