package com.checkSheet;

import com.checkSheet.config.FileStorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(scanBasePackages = {"com.checkSheet"})
@EnableScheduling
@EnableAsync
@EnableTransactionManagement
@EnableConfigurationProperties({
		FileStorageProperties.class
})
public class Application extends SpringBootServletInitializer {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean("threadPoolTaskExecutorForNotification")
	public TaskExecutor getAsyncExecutorForNotification() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(5);
		executor.setMaxPoolSize(20);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setThreadNamePrefix("Async-");
		return executor;
	}

	@Bean("threadPoolTaskExecutorForEmail")
	public TaskExecutor getAsyncExecutorForEmail() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(5);
		executor.setMaxPoolSize(20);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setThreadNamePrefix("Async-Email");
		return executor;
	}

	@Bean("threadPoolTaskExecutorForEmailWithAttachment")
	public TaskExecutor getAsyncExecutorForEmailWithAttachment() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(5);
		executor.setMaxPoolSize(20);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setThreadNamePrefix("Async-Email");
		return executor;
	}

	/**
	 * Bounded executor for the intervention-instantiation @TransactionalEventListener.
	 * Without this bean, @Async falls back to SimpleAsyncTaskExecutor which spawns a
	 * fresh thread per task (no pool, no queue, no backpressure) — under an
	 * approval-burst (batch approve) this would spawn thousands of threads. The
	 * bounded pool (core=5, max=20, queue=100) gives a safe upper bound and lets
	 * tasks queue rather than fork-bombing the JVM.
	 */
	@Bean("interventionListenerExecutor")
	public TaskExecutor getInterventionListenerExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(5);
		executor.setMaxPoolSize(20);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("Intervention-Listener-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		executor.initialize();
		return executor;
	}
}
