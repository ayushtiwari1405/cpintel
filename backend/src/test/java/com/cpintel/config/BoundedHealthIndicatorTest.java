package com.cpintel.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BoundedHealthIndicator: the wrapped check's answer when it comes in time, DOWN when it does not,
 * one check in flight at a time, and back to UP once the dependency is.
 */
class BoundedHealthIndicatorTest {

    private static final Duration BOUND = Duration.ofMillis(200);

    @Test
    @DisplayName("a check that answers in time is passed through, UP or DOWN")
    void passesThrough() {
        assertThat(new BoundedHealthIndicator("db", () -> Health.up().build(), BOUND).health().getStatus()).isEqualTo(Status.UP);
        assertThat(new BoundedHealthIndicator("db", () -> Health.down().build(), BOUND).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("a check that throws is DOWN, not an error out of the endpoint")
    void failureIsDown() {
        HealthIndicator broken = () -> { throw new IllegalStateException("pool closed"); };
        assertThat(new BoundedHealthIndicator("db", broken, BOUND).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("a check that hangs is DOWN within the bound, and later calls share it rather than start more")
    void hangingIsBoundedAndShared() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        HealthIndicator hanging = () -> {
            started.incrementAndGet();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Health.up().build();
        };
        BoundedHealthIndicator bounded = new BoundedHealthIndicator("db", hanging, BOUND);

        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            Health h = bounded.health();
            assertThat(h.getStatus()).isEqualTo(Status.DOWN);
            assertThat(h.getDetails()).containsEntry("error", "db did not answer within 200 ms");
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
        }
        assertThat(started.get()).isEqualTo(1);
        release.countDown();
    }

    @Test
    @DisplayName("once the dependency answers again, so does the check")
    void recovers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Status> state = new AtomicReference<>(Status.DOWN);
        HealthIndicator dependency = () -> {
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Health.status(state.get()).build();
        };
        BoundedHealthIndicator bounded = new BoundedHealthIndicator("db", dependency, BOUND);
        assertThat(bounded.health().getStatus()).isEqualTo(Status.DOWN);   // hanging

        state.set(Status.UP);
        release.countDown();
        Thread.sleep(50);
        assertThat(bounded.health().getStatus()).isEqualTo(Status.UP);
    }
}
