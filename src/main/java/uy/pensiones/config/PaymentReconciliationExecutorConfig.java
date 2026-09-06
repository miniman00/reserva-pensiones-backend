package uy.pensiones.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class PaymentReconciliationExecutorConfig {
    @Bean(name = "paymentReconciliationExecutor")
    public TaskExecutor paymentReconciliationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("payment-reconcile-");
        executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
