package org.iphoto.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ThreadPoolConfiguration {

    public static final String BACKEND_TASK = "backend-task";
    public static final String HEAVY_TASK = "heavy-task";

    @Bean("lightThreadPoolExecutor")
    ThreadPoolTaskExecutor lightThreadPoolExecutor () {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(10);
        taskExecutor.setMaxPoolSize(40);
        taskExecutor.setQueueCapacity(100);
        taskExecutor.setVirtualThreads(true);
        taskExecutor.setThreadGroupName(BACKEND_TASK);
        taskExecutor.setThreadNamePrefix(BACKEND_TASK);
        return taskExecutor;
    }

    @Bean("heavyThreadPoolExecutor")
    ThreadPoolTaskExecutor heavyThreadPoolExecutor () {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(3);
        taskExecutor.setMaxPoolSize(3);
        taskExecutor.setQueueCapacity(10);
        taskExecutor.setVirtualThreads(false);
        taskExecutor.setThreadGroupName(HEAVY_TASK);
        taskExecutor.setThreadNamePrefix(HEAVY_TASK);
        return taskExecutor;
    }
}
