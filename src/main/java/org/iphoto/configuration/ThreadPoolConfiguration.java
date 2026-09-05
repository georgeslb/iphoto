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

    // One dedicated, fixed-size pool per heavy task type (see IPhotoProperties.HeavyTasks for why
    // this replaced a single shared heavy pool) - each still named with the HEAVY_TASK prefix so
    // MainSpaceAspectConfiguration's thread-name check (which skips binding the per-request space
    // filter on background-task threads) keeps working unchanged for all three.
    @Bean("duplicationThreadPoolExecutor")
    ThreadPoolTaskExecutor duplicationThreadPoolExecutor() {
        return heavyThreadPoolExecutor("duplication-", iPhotoProperties.getHeavyTasks().getDuplicationThreads());
    }

    @Bean("faceDetectionThreadPoolExecutor")
    ThreadPoolTaskExecutor faceDetectionThreadPoolExecutor() {
        return heavyThreadPoolExecutor("face-detection-", iPhotoProperties.getHeavyTasks().getFaceDetectionThreads());
    }

    @Bean("faceRecognitionThreadPoolExecutor")
    ThreadPoolTaskExecutor faceRecognitionThreadPoolExecutor() {
        return heavyThreadPoolExecutor("face-recognition-", iPhotoProperties.getHeavyTasks().getFaceRecognitionThreads());
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
