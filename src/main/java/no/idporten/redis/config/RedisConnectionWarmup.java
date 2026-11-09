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
 * topology discovery: a Sentinel lookup, then connect and ping against every node, where each unreachable node
 * costs a connect-timeout.
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
     * Initializes both shared connections, the imperative and the reactive one, the same way
     * {@link LettuceConnectionFactory#setEagerInitialization(boolean) eager initialization} does, but without
     * failing the application when Redis cannot be reached.
     */
    void warmUp(LettuceConnectionFactory factory) {
        if (!factory.getShareNativeConnection()) {
            log.info("Redis connection warm-up skipped: the connection factory does not share a native connection");
            return;
        }
        if (!factory.isRunning()) {
            log.info("Redis connection warm-up skipped: the connection factory is not started");
            return;
        }
        long startNanos = System.nanoTime();
        try {
            factory.initConnection();
            log.info("Redis connection warm-up: shared connections ready after {} ms", elapsedMillis(startNanos));
        } catch (RuntimeException e) {
            // The message can contain Redis or Sentinel host names and ports, so it is only logged at debug level.
            log.warn("Redis connection warm-up failed after {} ms ({}), the first use will connect instead",
                    elapsedMillis(startNanos), NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName());
            log.debug("Redis connection warm-up failed", e);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

}
