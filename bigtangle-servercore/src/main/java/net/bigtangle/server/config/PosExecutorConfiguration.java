package net.bigtangle.server.config;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated thread pool for the consensus duty loop (slot tick + chain
 * connection). Keeping these on their own pool — instead of Spring's shared
 * async executor — isolates the consensus path from the submit/API burst so a
 * busy request worker can never delay a beacon proposal or block connection.
 */
@Configuration
public class PosExecutorConfiguration {

    @Bean(name = "posExecutor")
    public ThreadPoolTaskExecutor posExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        // Synchronous handoff: a second duty (e.g. block connection while the
        // slot tick runs) gets its own thread immediately instead of queuing
        // behind the first and missing its slot window.
        executor.setQueueCapacity(0);
        executor.setThreadFactory(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pos-duty-" + counter.incrementAndGet());
                t.setDaemon(true);
                // The consensus path must win CPU against the submit burst.
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            }
        });
        return executor;
    }
}