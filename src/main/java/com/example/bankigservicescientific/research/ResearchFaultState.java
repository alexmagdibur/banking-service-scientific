package com.example.bankigservicescientific.research;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The "moderated unavailability" window of this instance. The window starts shortly after activation (so the
 * confirmation reaches the caller first) and ends by itself; nothing has to be switched off. It lives only in
 * memory, so restarting the Pod also clears it.
 */
@Component
@Profile("research")
public class ResearchFaultState {

    static final Duration START_DELAY = Duration.ofMillis(300);
    private static final Logger log = LoggerFactory.getLogger("bank.research");

    private record Window(long startNanos, long endNanos) {
    }

    public record Activation(Instant startsAt, Instant endsAt) {
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "research-fault-timer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Window window;

    public Activation activate(int durationSeconds, String instanceId) {
        long now = System.nanoTime();
        long start = now + START_DELAY.toNanos();
        Window next = new Window(start, start + TimeUnit.SECONDS.toNanos(durationSeconds));
        window = next;
        Instant startsAt = Instant.now().plus(START_DELAY);
        Instant endsAt = startsAt.plusSeconds(durationSeconds);
        scheduler.schedule(() -> {
            if (window == next) {
                log.warn("research fault UNRESPONSIVE ended on instance {}", instanceId);
            }
        }, next.endNanos() - now, TimeUnit.NANOSECONDS);
        log.warn("research fault UNRESPONSIVE enabled on instance {}: starts {}, ends {} ({} s)", instanceId, startsAt,
                endsAt, durationSeconds);
        return new Activation(startsAt, endsAt);
    }

    public void clear(String instanceId) {
        if (window != null) {
            window = null;
            log.warn("research fault UNRESPONSIVE cleared manually on instance {}", instanceId);
        }
    }

    public boolean isActive() {
        Window current = window;
        if (current == null) {
            return false;
        }
        long now = System.nanoTime();
        return now - current.startNanos() >= 0 && now - current.endNanos() < 0;
    }

    /** Whole seconds until the window ends, rounded up (0 when it is not active). */
    public long remainingSeconds() {
        return isActive() ? TimeUnit.NANOSECONDS.toSeconds(remainingNanos() + 999_999_999L) : 0;
    }

    public long remainingNanos() {
        Window current = window;
        return current == null ? 0 : Math.max(0, current.endNanos() - System.nanoTime());
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
