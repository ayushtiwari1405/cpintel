package com.cpintel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "syncTaskExecutor")
    public Executor syncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("sync-");
        // Without this a queued sync starts with an empty logging context and its lines lose
        // the request id of whoever asked for it — the one case where correlation matters most,
        // since the work outlives the request by minutes.
        executor.setTaskDecorator(new MdcTaskDecorator());
        // The default policy throws RejectedExecutionException into the calling request thread,
        // which surfaced as a 500 — telling a user the server is broken when the honest answer
        // is that the queue is full. AbortPolicy is still the right shape (a sync is a long job
        // and running it on the request thread would block it for minutes); what changes is that
        // SyncService catches this and answers 503 with a Retry-After.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
