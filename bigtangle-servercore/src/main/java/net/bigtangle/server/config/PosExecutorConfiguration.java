package net.bigtangle.server.config;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PosExecutorConfiguration.class);

    @Bean(name = "posExecutor")
    public ThreadPoolTaskExecutor posExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // Synchronous handoff: the slot tick must start immediately on its own
        // dedicated thread. If a previous tick is still running, one tick is
        // parked in the queue and any FURTHER ticks replace it (latest-wins):
        // two slots can never run concurrently, but a long proposal can no
        // longer drop every subsequent slot — the newest tick always survives.
        executor.setQueueCapacity(1);
        executor.setRejectedExecutionHandler((r, e) -> {
            // Queue full: drop the STALE queued tick (its once-per-slot duty
            // guard makes re-running it pointless) and keep the newest.
            e.getQueue().poll();
            if (!e.getQueue().offer(r)) {
                log.warn("posExecutor dropped a slot tick (previous tick still running)");
            }
        });
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

    @Bean(name = "posChainExecutor")
    public ThreadPoolTaskExecutor posChainExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // The chain-connection loop (updateChain) is near-continuous under load;
        // give it its own bounded queue so a slow connect is never dropped (the
        // old shared pool rejected these once both pos-duty threads were busy).
        // It never shares a thread with the slot tick, so a long connect cannot
        // delay a beacon proposal.
        executor.setQueueCapacity(16);
        executor.setThreadFactory(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pos-chain-" + counter.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            }
        });
        return executor;
    }
}