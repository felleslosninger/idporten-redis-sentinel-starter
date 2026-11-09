package no.idporten.redis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

/**
 * Opens the shared Lettuce connections while the application is still starting, so the first request that needs
 * Redis does not pay for the connection setup. With Sentinel and a read-from, that setup is a full Master/Replica
 * topology discovery: a Sentinel lookup, then connect and ping against every node. The node connects run
 * concurrently, so unreachable nodes cost one connect-timeout per discovery stage (Lettuce default 10 s).
 * <p>
 * This matters most for the readiness probe. Boot's reactive {@code redis} health indicator uses the shared
 * reactive connection, which nothing else in the application has touched when the first probe arrives, so without
 * this the probe blocks for the whole topology discovery and kubelet gives up on it. Runs on
 * {@link ApplicationStartedEvent}, which is published before liveness is set to CORRECT, so the startup probe
 * covers the time the warm-up takes.
 * <p>
 * A failed warm-up is logged and otherwise ignored: an application that starts while Redis is down still starts,
 * and the first use connects instead, exactly as without the warm-up.
 */
public class RedisConnectionWarmup {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionWarmup.class);

    private final ObjectProvider<RedisConnectionFactory> connectionFactory;

    public RedisConnectionWarmup(ObjectProvider<RedisConnectionFactory> connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void warmUp() {
        if (connectionFactory.getIfAvailable() instanceof LettuceConnectionFactory lettuceConnectionFactory) {
            warmUp(lettuceConnectionFactory);
        } else {
            log.debug("Redis connection warm-up skipped: no LettuceConnectionFactory in context");
        }
    }

    /**
     * Opens both shared connections, the imperative and the reactive one, without failing the application when
     * Redis cannot be reached. {@code validateConnection()} pings a shared connection that is already open, e.g.
     * one Spring Session opened during context refresh, and only creates the ones that are missing or dead.
     * {@code initConnection()} would reset the open one first and repeat its topology discovery. Spring Data logs
     * "Validation of shared connection failed; Creating a new connection." at INFO for each connection it creates
     * here; that is the expected first-time path, not an error.
     * <p>
     * The call is bounded by Lettuce's timeouts only: in the worst case, a half-open Sentinel or node that accepts
     * TCP but never answers, it takes about {@code 2 x spring.data.redis.timeout + spring.data.redis.connect-timeout},
     * which must fit inside the startup probe's budget.
     */
    void warmUp(LettuceConnectionFactory factory) {
        if (!factory.getShareNativeConnection()) {
            log.info("Redis connection warm-up skipped: the connection factory does not share a native connection");
            return;
        }
        if (!factory.isRunning()) {
            // Unreachable in a Boot application: the factory starts in afterPropertiesSet()
            log.debug("Redis connection warm-up skipped: the connection factory is not started");
            return;
        }
        long startNanos = System.nanoTime();
        try {
            factory.validateConnection();
            log.info("Redis connection warm-up: shared connections ready after {} ms", elapsedMillis(startNanos));
        } catch (RuntimeException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            log.warn("Redis connection warm-up failed after {} ms, the first use will connect instead: {}: {}",
                    elapsedMillis(startNanos), cause.getClass().getSimpleName(), cause.getMessage());
            log.debug("Redis connection warm-up failed", e);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

}
