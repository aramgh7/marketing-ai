package com.marketingai.scheduler;

import com.marketingai.service.EventPartitionMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Roadmap 9-6: keep monthly {@code events} partitions ahead of time. Runs at startup and on the 1st of
 * every month at 00:10 UTC; creates this month + 2 if missing through the SECURITY DEFINER function
 * {@code ensure_events_partition} (V128) because the app role does not own the parent table.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPartitionScheduler implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        ensurePartitions();
    }

    @Scheduled(cron = "0 10 0 1 * *", zone = "UTC")
    public void ensurePartitions() {
        int created = 0;
        for (EventPartitionMath.Partition p : EventPartitionMath.required(LocalDate.now())) {
            try {
                Boolean made = jdbc.queryForObject(EventPartitionMath.ENSURE_SQL, Boolean.class,
                        EventPartitionMath.ensureArgs(p));
                if (Boolean.TRUE.equals(made)) {
                    created++;
                    log.info("EventPartitionScheduler: created partition {} [{}, {})", p.name(), p.from(), p.to());
                }
            } catch (Exception e) {
                log.warn("EventPartitionScheduler: {} skipped: {}", p.name(), e.getMessage());
            }
        }
        if (created == 0) log.debug("EventPartitionScheduler: all partitions present");
    }
}
