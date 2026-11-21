package com.cpintel.integration.domjudge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A judge that stops answering is refused at once, then probed, instead of held on for 20 s a call. */
class JudgeBreakerTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final JudgeBreaker breaker = new JudgeBreaker("judge", now::get);

    private void fail(int times) { for (int i = 0; i < times; i++) breaker.failed(); }

    @Test
    @DisplayName("closed until five failures in a row; a success in between starts the count again")
    void opensAfterFiveInARow() {
        fail(JudgeBreaker.THRESHOLD - 1);
        assertThat(breaker.allow()).isTrue();
        breaker.succeeded();
        fail(JudgeBreaker.THRESHOLD - 1);
        assertThat(breaker.allow()).isTrue();
        breaker.failed();
        assertThat(breaker.allow()).isFalse();
        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    @DisplayName("after the cooldown exactly one caller probes; the others are still refused")
    void oneProbe() {
        fail(JudgeBreaker.THRESHOLD);
        now.addAndGet(JudgeBreaker.COOLDOWN_MS);
        assertThat(breaker.allow()).isTrue();
        assertThat(breaker.allow()).isFalse();
        assertThat(breaker.allow()).isFalse();
    }

    @Test
    @DisplayName("a probe that answers closes it; one that fails opens it again for a full cooldown")
    void probeOutcome() {
        fail(JudgeBreaker.THRESHOLD);
        now.addAndGet(JudgeBreaker.COOLDOWN_MS);
        assertThat(breaker.allow()).isTrue();
        breaker.failed();
        assertThat(breaker.allow()).isFalse();
        now.addAndGet(JudgeBreaker.COOLDOWN_MS - 1);
        assertThat(breaker.allow()).isFalse();
        now.addAndGet(1);
        assertThat(breaker.allow()).isTrue();
        breaker.succeeded();
        assertThat(breaker.allow()).isTrue();
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a probe that never answers does not wedge it: another may try after the probe window")
    void lostProbe() {
        fail(JudgeBreaker.THRESHOLD);
        now.addAndGet(JudgeBreaker.COOLDOWN_MS);
        assertThat(breaker.allow()).isTrue();
        now.addAndGet(JudgeBreaker.PROBE_MS);
        assertThat(breaker.allow()).isTrue();
    }

    private static ClientRequest request() {
        return ClientRequest.create(HttpMethod.GET, URI.create("http://judge.invalid/api/v4/info")).build();
    }

    @Test
    @DisplayName("through the filter: transport errors and 502-504 count, 4xx and 5xx like 500 do not")
    void whatCounts() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(503);
        ExchangeFunction next = req -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.valueOf(status.get())).build());
        };
        var filter = breaker.filter();
        for (int i = 0; i < JudgeBreaker.THRESHOLD; i++) filter.filter(request(), next).block();
        assertThat(breaker.isOpen()).isTrue();

        // Open: refused without reaching the judge, as a transport failure callers already handle.
        int before = calls.get();
        assertThatThrownBy(() -> filter.filter(request(), next).block())
            .isInstanceOf(WebClientRequestException.class).hasCauseInstanceOf(ConnectException.class);
        assertThat(calls.get()).isEqualTo(before);

        now.addAndGet(JudgeBreaker.COOLDOWN_MS);
        status.set(404);   // the judge answered: it is up, whatever it said
        filter.filter(request(), next).block();
        assertThat(breaker.isOpen()).isFalse();
        for (int i = 0; i < 10; i++) { status.set(500); filter.filter(request(), next).block(); }
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a refused connection or a timeout through the filter counts; another error does not")
    void errorsThroughTheFilter() {
        var filter = breaker.filter();
        ExchangeFunction refused = req -> Mono.error(new WebClientRequestException(
            new ConnectException("refused"), HttpMethod.GET, req.url(), req.headers()));
        for (int i = 0; i < JudgeBreaker.THRESHOLD; i++) {
            assertThatThrownBy(() -> filter.filter(request(), refused).block())
                .isInstanceOf(WebClientRequestException.class);
        }
        assertThat(breaker.isOpen()).isTrue();

        JudgeBreaker other = new JudgeBreaker("other", now::get);
        ExchangeFunction bug = req -> Mono.error(new IllegalArgumentException("not the judge's fault"));
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> other.filter().filter(request(), bug).block())
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(other.isOpen()).isFalse();
        assertThat(JudgeBreaker.isTransportFailure(new RuntimeException(
            new io.netty.handler.timeout.ReadTimeoutException()))).isTrue();
    }
}
