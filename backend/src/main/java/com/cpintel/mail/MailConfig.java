package com.cpintel.mail;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * The thread pool mail is sent on.
 *
 * <p>Small and bounded, with a bounded queue and a caller-runs policy deliberately <em>not</em>
 * used: if the queue fills, the send is dropped rather than pushed back onto the request
 * thread. A backlog of reset emails is a problem; a request thread blocked on an SMTP
 * handshake because of one is a worse problem, and the message this drops is a notification
 * about something that already succeeded.
 */
@Configuration
@EnableAsync
public class MailConfig {

    @Bean(name = "mailExecutor")
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mail-");
        // Drop rather than block. See the class note.
        executor.setRejectedExecutionHandler((r, e) -> { /* dropped; MailService logs sends */ });
        executor.initialize();
        return executor;
    }
}
