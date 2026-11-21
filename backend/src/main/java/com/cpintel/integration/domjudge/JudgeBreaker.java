package com.cpintel.integration.domjudge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Stops a judge that has gone quiet from tying up the server's request threads.
 *
 * <p>Each call to DOMjudge blocks the request thread that made it, for up to the socket
 * timeout (20 s). While the judge answers, that is fine. When it stops answering mid-exam,
 * every candidate's poll, submit and leaderboard read waits out the full timeout, and the
 * threads Tomcat shares with <em>everything</em> (heartbeats, the paper, sign-in) fill up with
 * waits: CPIntel looks down although only the judge is.
 *
 * <p>After {@value #THRESHOLD} consecutive transport failures (no connection, a timeout, or a
 * 502/503/504 from something in front of the judge) the breaker opens for {@value #COOLDOWN_MS}
 * ms, and calls fail at once with the same exception a refused connection raises, so every
 * caller's existing handling applies and nothing waits. When the time is up one call is let
 * through as a probe; if it succeeds the breaker closes, if not it opens again. An answer of
 * any other kind, 4xx included, proves the judge is up and closes it.
 *
 * <p>One per judge client, so one classroom's dead judge does not stop another's.
 */
@Slf4j
final class JudgeBreaker {

    static final int THRESHOLD = 5;
    static final long COOLDOWN_MS = 10_000;
    /** How long a probe has to answer before another caller may try. */
    static final long PROBE_MS = 5_000;

    private final String name;
    private final LongSupplier clock;
    private final AtomicInteger failures = new AtomicInteger();
    /** 0 while closed; otherwise the time before which calls are refused. */
    private final AtomicLong openUntil = new AtomicLong();

    JudgeBreaker(String name) {
        this(name, System::currentTimeMillis);
    }

    JudgeBreaker(String name, LongSupplier clock) {
        this.name = name;
        this.clock = clock;
    }

    boolean allow() {
        long until = openUntil.get();
        if (until == 0) return true;
        long now = clock.getAsLong();
        if (now < until) return false;
        // Cooled down: exactly one caller wins the probe, the rest are refused until it answers.
        return openUntil.compareAndSet(until, now + PROBE_MS);
    }

    void succeeded() {
        failures.set(0);
        if (openUntil.getAndSet(0) != 0) log.info("{} answers again; calls resume", name);
    }

    void failed() {
        if (failures.incrementAndGet() >= THRESHOLD) {
            if (openUntil.getAndSet(clock.getAsLong() + COOLDOWN_MS) == 0) {
                log.warn("{} did not answer {} times in a row; refusing calls for {} s", name,
                    THRESHOLD, COOLDOWN_MS / 1000);
            }
        }
    }

    boolean isOpen() {
        long until = openUntil.get();
        return until != 0 && clock.getAsLong() < until;
    }

    static boolean isTransportFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConnectException || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof io.netty.handler.timeout.TimeoutException
                    || t instanceof WebClientRequestException) return true;
        }
        return false;
    }

    ExchangeFilterFunction filter() {
        return (ClientRequest request, org.springframework.web.reactive.function.client.ExchangeFunction next) -> {
            if (!allow()) {
                return Mono.error(new WebClientRequestException(
                    new ConnectException(name + " is not answering; not trying again yet"),
                    request.method(), request.url(), HttpHeaders.EMPTY));
            }
            return next.exchange(request)
                .doOnNext(response -> {
                    int code = response.statusCode().value();
                    if (code == 502 || code == 503 || code == 504) failed(); else succeeded();
                })
                .doOnError(e -> { if (isTransportFailure(e)) failed(); });
        };
    }
}
