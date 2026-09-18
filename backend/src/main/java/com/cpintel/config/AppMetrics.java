package com.cpintel.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The questions this deployment actually raises, expressed as meters.
 *
 * <p>Micrometer, Prometheus and Grafana were already wired and scraping, and a
 * {@code TimedAspect} bean was registered — but {@code @Timed} appeared nowhere, so the aspect
 * measured nothing. The dashboards could show heap and HTTP status codes and could not answer a
 * single question specific to this system: how often does a Codeforces sync fail, how slow is
 * the judge today, how many runs are timing out, is anyone being throttled.
 *
 * <p>Everything is funnelled through one class so the metric names stay consistent and so the
 * tag cardinality stays deliberate. Tags are bounded sets — a platform, a verdict, an outcome —
 * never a user id or a problem id, because Prometheus keeps a distinct time series per tag
 * combination and unbounded tags are how a metrics backend gets taken down by its own data.
 */
@Component
@RequiredArgsConstructor
public class AppMetrics {

    private final MeterRegistry registry;

    // ------------------------------------------------------------------ sync

    /** One per finished sync, tagged with which judge and whether it worked. */
    public void syncFinished(String platform, boolean success) {
        registry.counter("cpintel.sync.completed",
            "platform", platform,
            "outcome", success ? "success" : "failure").increment();
    }

    public void syncItemsStored(String platform, int count) {
        if (count > 0) {
            registry.counter("cpintel.sync.items", "platform", platform).increment(count);
        }
    }

    /** A sync refused because the executor queue was full — the 503 path. */
    public void syncRejected(String platform) {
        registry.counter("cpintel.sync.rejected", "platform", platform).increment();
    }

    // ------------------------------------------------- outbound platform calls

    /**
     * How long a judge took to answer, and whether it did.
     *
     * The single most useful series here: when sync slows down, this says whether the cause is
     * inside this application or at the other end of the wire.
     */
    public void platformCall(String platform, String operation, boolean success, Duration took) {
        Timer.builder("cpintel.platform.call")
            .tag("platform", platform)
            .tag("operation", operation)
            .tag("outcome", success ? "success" : "failure")
            .register(registry)
            .record(took);
    }

    /** Time spent waiting for a slot on a judge's shared timeline, rather than on the judge. */
    public void outboundWait(String platform, Duration waited) {
        Timer.builder("cpintel.platform.rate_limit_wait")
            .tag("platform", platform)
            .register(registry)
            .record(waited);
    }

    /** A call given up on because the queue for that judge was longer than the caller allowed. */
    public void outboundBackpressure(String platform) {
        registry.counter("cpintel.platform.backpressure", "platform", platform).increment();
    }

    // ---------------------------------------------------------------- runner

    /** One per test case judged, tagged by verdict — OK, WRONG_ANSWER, TIME_LIMIT_EXCEEDED… */
    public void runVerdict(String verdict) {
        registry.counter("cpintel.run.verdict", "verdict", verdict).increment();
    }

    public void runCompiled(boolean success, Duration took) {
        Timer.builder("cpintel.run.compile")
            .tag("outcome", success ? "success" : "failure")
            .register(registry)
            .record(took);
    }

    // ------------------------------------------------------------- throttling

    /** Someone hit a rate limit. Tagged by bucket, never by who — that is the audit trail's job. */
    public void throttled(String bucket) {
        registry.counter("cpintel.ratelimit.throttled", "bucket", bucket).increment();
    }
}
