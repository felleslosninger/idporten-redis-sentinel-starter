package no.idporten.redis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Logs how the Lettuce connection factory is set up, so the Sentinel/Master-Replica mode can be confirmed from the
 * pod log instead of guessed from the config map.
 */
public class RedisConnectionStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionStartupLogger.class);

    private final ObjectProvider<RedisConnectionFactory> connectionFactory;

    public RedisConnectionStartupLogger(ObjectProvider<RedisConnectionFactory> connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logConnectionMode() {
        RedisConnectionFactory factory = connectionFactory.getIfAvailable();
        if (factory instanceof LettuceConnectionFactory lettuceConnectionFactory) {
            logConnectionMode(lettuceConnectionFactory);
        } else {
            log.info("Redis connection: no LettuceConnectionFactory in context ({})", factory == null ? "none" : factory.getClass().getSimpleName());
        }
    }

    void logConnectionMode(LettuceConnectionFactory factory) {
        LettuceClientConfiguration clientConfiguration = factory.getClientConfiguration();
        boolean sentinel = factory.isRedisSentinelAware();
        String readFrom = clientConfiguration.getReadFrom().map(r -> r.getClass().getSimpleName()).orElse("none");
        boolean masterReplica = sentinel && clientConfiguration.getReadFrom().isPresent();
        log.info("Redis connection: sentinel={}, readFrom={}, masterReplicaTopology={}, sharedNativeConnection={}",
                sentinel, readFrom, masterReplica, factory.getShareNativeConnection());
        if (sentinel && !masterReplica) {
            log.warn("Redis Sentinel is configured without a read-from: the client will not follow +switch-master "
                    + "and can keep writing to a demoted master (READONLY) until the connection is reset");
        }
    }

}
