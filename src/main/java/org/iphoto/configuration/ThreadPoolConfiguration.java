package org.iphoto.configuration;

import lombok.RequiredArgsConstructor;
import org.iphoto.properties.IPhotoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@RequiredArgsConstructor
public class ThreadPoolConfiguration {

    public static final String BACKEND_TASK = "backend-task";
    public static final String MIDDLE_TASK = "middle-task";
    public static final String HEAVY_TASK = "heavy-task";

    private final IPhotoProperties iPhotoProperties;

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

    @Bean("middleThreadPoolExecutor")
    ThreadPoolTaskExecutor middleThreadPoolExecutor () {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(iPhotoProperties.getHeavyTasks().getMiddleTaskThreads());
        taskExecutor.setMaxPoolSize(iPhotoProperties.getHeavyTasks().getMiddleTaskThreads());
        taskExecutor.setQueueCapacity(100);
        taskExecutor.setVirtualThreads(false);
        taskExecutor.setThreadGroupName(MIDDLE_TASK);
        taskExecutor.setThreadNamePrefix(MIDDLE_TASK);
        return taskExecutor;
    }



    @Bean("faceRecognitionThreadPoolExecutor")
    ThreadPoolTaskExecutor faceRecognitionThreadPoolExecutor() {
        return heavyThreadPoolExecutor("recognition-", iPhotoProperties.getHeavyTasks().getFaceRecognitionThreads());
    }

    @Bean("duplicationThreadPoolExecutor")
    ThreadPoolTaskExecutor duplicationThreadPoolExecutor() {
        return heavyThreadPoolExecutor("duplication-", iPhotoProperties.getHeavyTasks().getDuplicationThreads());
    }

    private ThreadPoolTaskExecutor heavyThreadPoolExecutor(String threadNameSuffix, int poolSize) {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(poolSize);
        taskExecutor.setMaxPoolSize(poolSize);
        taskExecutor.setQueueCapacity(iPhotoProperties.getHeavyTasks().getQueueCapacity());
        taskExecutor.setVirtualThreads(false);
        taskExecutor.setThreadGroupName(HEAVY_TASK);
        taskExecutor.setThreadNamePrefix(HEAVY_TASK + "-" + threadNameSuffix);
        return taskExecutor;
    }
}
