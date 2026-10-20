package com.marketingai.config;

import com.marketingai.security.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * Every {@code @Scheduled} job runs in {@link TenantContext#runAsSystem system scope}.
 *
 * Audit 3.1: 36 of 37 schedulers never set a tenant. Once the app connects as the non-superuser
 * {@code mai_app} role, RLS would return zero rows to all of them and every job would silently do
 * nothing. Jobs are platform-level by design (they iterate or aggregate across tenants), so they get
 * an explicit, auditable bypass here instead of a per-tenant loop in 37 files. HTTP requests never
 * receive this scope — it is granted only on the scheduler threads for the duration of a task.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new SystemScopedTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setScheduler(taskScheduler());
    }

    /** Wraps each scheduled Runnable so the system scope is set on the executing thread. */
    static class SystemScopedTaskScheduler extends ThreadPoolTaskScheduler {

        private static Runnable scoped(Runnable task) {
            return () -> TenantContext.runAsSystem(task);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return super.schedule(scoped(task), trigger);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return super.schedule(scoped(task), startTime);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return super.scheduleAtFixedRate(scoped(task), startTime, period);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return super.scheduleAtFixedRate(scoped(task), period);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return super.scheduleWithFixedDelay(scoped(task), startTime, delay);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return super.scheduleWithFixedDelay(scoped(task), delay);
        }
    }
}
