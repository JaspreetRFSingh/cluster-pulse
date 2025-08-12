package com.clusterpulse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
}
