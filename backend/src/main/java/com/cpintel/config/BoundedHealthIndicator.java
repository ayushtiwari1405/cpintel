package com.cpintel.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A health check that answers within a bound, whatever the check it wraps does.
 *
 * <p>Some checks can only be as quick as a timeout that exists for other reasons: the database
 * check borrows a pooled connection, and the pool waits up to {@code hikari.connection-timeout}
 * (30 s) - a wait that also covers a busy pool under load, so it is not one to shorten. With the
 * database gone, every health call then took 30 s to say DOWN; Docker's check (10 s) saw
 * nothing but timeouts, and deploy.sh waited out its window.
 *
 * <p>Past the bound the answer is DOWN, which is the truth: a database that cannot hand out a
 * connection within it cannot serve requests either. At most one check is in flight: a call that
 * arrives while one is still running waits on that one rather than starting another, so a
 * dependency that hangs costs one thread, not one per health call.
 */
public class BoundedHealthIndicator implements HealthIndicator {

    private final String name;
    private final HealthIndicator delegate;
    private final Duration bound;
    private final ExecutorService executor;
    private Future<Health> inFlight;

    public BoundedHealthIndicator(String name, HealthIndicator delegate, Duration bound) {
        this.name = name;
        this.delegate = delegate;
        this.bound = bound;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "health-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    private synchronized Future<Health> check() {
        if (inFlight == null || inFlight.isDone()) {
            inFlight = executor.submit(delegate::health);
        }
        return inFlight;
    }

    @Override
    public Health health() {
        Future<Health> check = check();
        try {
            return check.get(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return Health.down().withDetail("error", name + " did not answer within " + bound.toMillis() + " ms").build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("error", "interrupted").build();
        } catch (ExecutionException e) {
            return Health.down().withDetail("error", String.valueOf(e.getCause())).build();
        }
    }
}
