package com.cpintel.integration;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * One {@link WebClient} per judge, built once at startup.
 *
 * <p>Each client used to call {@code webClientBuilder.baseUrl(...).build()} inside every request
 * method, constructing a client — and its connection pool — per call. It worked, because the
 * underlying connection provider is shared by default, but it made a fresh object graph for
 * every outbound request and left the pool's behaviour to whatever the default happened to be.
 *
 * <p>The timeouts are the more important half. Reactor Netty applies none by default beyond what
 * the OS gives you, so a judge that accepts a connection and then stops responding could hold a
 * sync thread until the TCP stack gave up — minutes, on a pool of eight. Every call already had
 * a {@code .block(Duration)} guarding the outer wait; these guard the socket underneath it, and
 * a slow upstream now fails as a timeout rather than as a thread that never comes back.
 */
@Configuration
@Slf4j
public class PlatformWebClients {

    /** Long enough for a healthy judge under load, short enough not to hoard a sync thread. */
    private static final Duration CONNECT_TIMEOUT  = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);

    /** Scraped pages are far larger than API responses; the default 256 KB is not enough. */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    @Bean
    public WebClient platformWebClient(WebClient.Builder builder) {
        ConnectionProvider pool = ConnectionProvider.builder("platform")
            .maxConnections(32)
            // Rather than queue forever behind a saturated pool: the outbound limiter is what
            // paces these, so a long wait here means something is wrong, not merely busy.
            .pendingAcquireTimeout(Duration.ofSeconds(15))
            .maxIdleTime(Duration.ofSeconds(45))
            .build();

        HttpClient httpClient = HttpClient.create(pool)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
            .responseTimeout(RESPONSE_TIMEOUT)
            .doOnConnected(conn -> conn.addHandlerLast(
                new ReadTimeoutHandler(RESPONSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)));

        return builder
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
            .build();
    }
}
