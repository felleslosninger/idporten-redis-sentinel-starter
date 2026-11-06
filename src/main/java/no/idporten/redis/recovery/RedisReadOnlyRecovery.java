package no.idporten.redis.recovery;

import jakarta.annotation.PreDestroy;
import no.idporten.redis.config.RedisSentinelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Recovers from being connected to a Redis node that has been demoted to replica (READONLY), e.g. after a Sentinel
 * failover during patching. Neither Lettuce nor Spring Data Redis re-resolves the master on READONLY, and the redis
 * health indicator passes on a replica, so the pod would otherwise keep taking traffic it cannot serve until it is
 * restarted.
 * <p>
 * Detection is a periodic write probe, so it works for every application regardless of how it uses Redis. An
 * application that catches the READONLY error itself can also call {@link #onReadOnly()} directly to react at once.
 * <p>
 * On READONLY the pod stops accepting traffic (readiness), the Lettuce connection is reset so the master is looked up
 * again through Sentinel, and the write probe verifies the result. If the probe succeeds the pod accepts traffic
 * again. If it still fails after the configured number of attempts, liveness is set to BROKEN so the pod is restarted.
 */
public class RedisReadOnlyRecovery {

    private static final Logger log = LoggerFactory.getLogger(RedisReadOnlyRecovery.class);

    private enum State {
        IDLE,
        RECOVERING,
        /**
         * Terminal: liveness is BROKEN and the pod is waiting to be restarted, so there is nothing more to try.
         */
        GAVE_UP
    }

    private final RedisSentinelProperties.Recovery properties;
    private final RedisWriteProbe writeProbe;
    private final ObjectProvider<RedisConnectionFactory> connectionFactory;
    private final ApplicationAvailability applicationAvailability;
    private final ApplicationEventPublisher eventPublisher;

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("redis-recovery-", 0).factory());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "redis-write-probe");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicBoolean refusingTraffic = new AtomicBoolean();
    private final AtomicBoolean probeScheduled = new AtomicBoolean();
    private volatile long cooldownUntilNanos = System.nanoTime();
    private volatile RedisWriteProbe.Result lastProbeResult = RedisWriteProbe.Result.WRITABLE;

    public RedisReadOnlyRecovery(RedisSentinelProperties.Recovery properties,
                                 RedisWriteProbe writeProbe,
                                 ObjectProvider<RedisConnectionFactory> connectionFactory,
                                 ApplicationAvailability applicationAvailability,
                                 ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.writeProbe = writeProbe;
        this.connectionFactory = connectionFactory;
        this.applicationAvailability = applicationAvailability;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Starts recovery unless it is disabled, already running, given up, or the previous recovery finished within the
     * cooldown. Safe to call from request threads: the recovery itself runs in the background.
     */
    public void onReadOnly() {
        if (!properties.isEnabled() || !state.compareAndSet(State.IDLE, State.RECOVERING)) {
            return;
        }
        // Checked after winning the state change: the cooldown is written before the state goes back to IDLE, so a
        // caller that gets here always sees the cooldown of the recovery that just finished.
        if (inCooldown()) {
            state.set(State.IDLE);
            return;
        }
        log.warn("Redis answered READONLY, refusing traffic and resetting the connection");
        refuseTraffic();
        try {
            executor.execute(this::recover);
        } catch (RuntimeException e) {
            acceptTraffic();
            state.set(State.IDLE);
            throw e;
        }
    }

    /**
     * Readiness changes made by anyone else, e.g. graceful shutdown, take over from our refusal, so we must not undo
     * them. The periodic write probe starts when the application first accepts traffic, with the first run at once:
     * pods that start during a failover may connect to the old master from the beginning. Events we publish ourselves
     * are ignored.
     */
    @EventListener
    public void onReadinessChange(AvailabilityChangeEvent<ReadinessState> event) {
        if (event.getSource() == this) {
            return;
        }
        refusingTraffic.set(false);
        if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC
                && properties.isEnabled()
                && probeScheduled.compareAndSet(false, true)) {
            scheduleProbe();
        }
    }

    private void scheduleProbe() {
        try {
            long intervalMillis = properties.getProbeInterval() == null ? 0 : properties.getProbeInterval().toMillis();
            if (intervalMillis > 0) {
                scheduler.scheduleWithFixedDelay(this::periodicProbe, 0, intervalMillis, TimeUnit.MILLISECONDS);
            } else {
                scheduler.execute(this::periodicProbe);
            }
        } catch (RejectedExecutionException e) {
            log.debug("Skipping the Redis write probe, the application context is closing");
        }
    }

    /**
     * Only probes while idle: during recovery the recovery loop probes itself, and after giving up there is nothing
     * left to do. Never throws, since an exception would cancel the periodic schedule.
     */
    void periodicProbe() {
        try {
            if (state.get() != State.IDLE || inCooldown()) {
                return;
            }
            RedisWriteProbe.Result result = writeProbe.probe();
            logProbeResultChange(result);
            if (result == RedisWriteProbe.Result.READ_ONLY) {
                onReadOnly();
            }
        } catch (RuntimeException e) {
            log.warn("Redis write probe failed unexpectedly: {}", e.getMessage());
        }
    }

    private void logProbeResultChange(RedisWriteProbe.Result result) {
        RedisWriteProbe.Result previous = lastProbeResult;
        lastProbeResult = result;
        if (result == previous) {
            return;
        }
        switch (result) {
            case UNAVAILABLE -> log.warn("Redis write probe failed: Redis is unavailable");
            case WRITABLE -> {
                if (previous == RedisWriteProbe.Result.UNAVAILABLE) {
                    log.info("Redis write probe succeeded: Redis is available again");
                }
            }
            case READ_ONLY -> {
                // onReadOnly() logs when it starts recovery
            }
        }
    }

    /**
     * Stops a recovery that is still running when the application context closes.
     */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    /**
     * Only READONLY answers count towards max-attempts. If Redis or Sentinel is unreachable, restarting the pod does
     * not help, and restarting every pod at once would turn a short outage into a crash loop. So the pod keeps
     * refusing traffic and trying until Redis answers, one way or the other. But not forever: after
     * max-unavailable-duration of unbroken silence liveness is set to BROKEN, because a restart does help if the
     * client is stuck on a broken connection that resetConnection() cannot fix.
     */
    void recover() {
        int readOnlyAttempts = 0;
        Long unavailableSinceNanos = null;
        try {
            for (int attempt = 1; ; attempt++) {
                resetConnection();
                switch (writeProbe.probe()) {
                    case WRITABLE -> {
                        log.info("Redis accepts writes again after {} attempt(s), accepting traffic", attempt);
                        lastProbeResult = RedisWriteProbe.Result.WRITABLE;
                        cooldownUntilNanos = System.nanoTime() + properties.getCooldown().toNanos();
                        acceptTraffic();
                        state.set(State.IDLE);
                        return;
                    }
                    case READ_ONLY -> {
                        unavailableSinceNanos = null;
                        readOnlyAttempts++;
                        if (readOnlyAttempts >= properties.getMaxAttempts()) {
                            log.error("Redis still read-only after {} attempts, setting liveness to BROKEN", readOnlyAttempts);
                            giveUp();
                            return;
                        }
                        log.warn("Redis still read-only, attempt {} of {}", readOnlyAttempts, properties.getMaxAttempts());
                    }
                    case UNAVAILABLE -> {
                        if (unavailableSinceNanos == null) {
                            unavailableSinceNanos = System.nanoTime();
                        }
                        Duration unavailableFor = Duration.ofNanos(System.nanoTime() - unavailableSinceNanos);
                        if (unavailableFor.compareTo(properties.getMaxUnavailableDuration()) >= 0) {
                            log.error("Redis unavailable for {}, setting liveness to BROKEN", unavailableFor);
                            giveUp();
                            return;
                        }
                        log.warn("Redis unavailable during recovery, attempt {}, trying again", attempt);
                    }
                }
                Thread.sleep(properties.getRetryInterval());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.set(State.IDLE);
        } catch (RuntimeException e) {
            log.error("Redis recovery failed unexpectedly, setting liveness to BROKEN", e);
            giveUp();
        }
    }

    boolean isRecovering() {
        return state.get() == State.RECOVERING;
    }

    private void giveUp() {
        state.set(State.GAVE_UP);
        AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.BROKEN);
    }

    private void resetConnection() {
        if (connectionFactory.getIfAvailable() instanceof LettuceConnectionFactory lettuceConnectionFactory) {
            lettuceConnectionFactory.resetConnection();
        } else {
            log.warn("The Redis connection factory is not a LettuceConnectionFactory, so the connection cannot be reset and the probe runs against the same connection");
        }
    }

    private boolean inCooldown() {
        return System.nanoTime() - cooldownUntilNanos < 0;
    }

    private void refuseTraffic() {
        if (applicationAvailability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC) {
            refusingTraffic.set(true);
            AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
        }
    }

    /**
     * Only undoes our own refusal, so a pod that is refusing traffic for another reason stays that way.
     */
    private void acceptTraffic() {
        if (refusingTraffic.getAndSet(false)) {
            AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

}
