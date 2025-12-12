package com.clusterpulse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

@Configuration
public class SchedulerConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("health-poller-");
        scheduler.setErrorHandler(throwable ->
                org.slf4j.LoggerFactory.getLogger("HealthPoller")
                        .error("Scheduled task error: {}", throwable.getMessage(), throwable));
        return scheduler;
    }

    /**
     * Dedicated executor for parallel cluster polling inside the health check cycle.
     * Sized to handle bursts of concurrent cluster connections without starving
     * the scheduler's own thread pool.
     */
    @Bean
    public Executor clusterPollExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("cluster-poll-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
