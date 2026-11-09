package no.idporten.redis.autoconfigure;

import no.idporten.redis.config.RedisConnectionStartupLogger;
import no.idporten.redis.config.RedisConnectionWarmup;
import no.idporten.redis.config.RedisSentinelProperties;
import no.idporten.redis.recovery.RedisReadOnlyRecovery;
import no.idporten.redis.recovery.RedisWriteProbe;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * READONLY detection and recovery for any application with a Redis connection factory. The startup log line and
 * the connection warm-up are registered here too, so they are available whenever the factory is.
 */
@AutoConfiguration(afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(LettuceConnectionFactory.class)
@ConditionalOnBean(RedisConnectionFactory.class)
@EnableConfigurationProperties(RedisSentinelProperties.class)
public class RedisReadOnlyRecoveryAutoConfiguration {

    static final String PROBE_KEY_SUFFIX = ":redis-sentinel:write-probe";

    @Bean
    @ConditionalOnMissingBean
    RedisConnectionStartupLogger redisConnectionStartupLogger(ObjectProvider<RedisConnectionFactory> connectionFactory) {
        return new RedisConnectionStartupLogger(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "idporten.redis-sentinel.warmup", name = "enabled", havingValue = "true", matchIfMissing = true)
    RedisConnectionWarmup redisConnectionWarmup(ObjectProvider<RedisConnectionFactory> connectionFactory) {
        return new RedisConnectionWarmup(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "idporten.redis-sentinel.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    RedisWriteProbe redisWriteProbe(ObjectProvider<RedisConnectionFactory> connectionFactory, Environment environment) {
        String applicationName = environment.getProperty("spring.application.name", "application");
        return new RedisWriteProbe(connectionFactory, applicationName + PROBE_KEY_SUFFIX);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "idporten.redis-sentinel.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    RedisReadOnlyRecovery redisReadOnlyRecovery(RedisSentinelProperties properties,
                                                RedisWriteProbe writeProbe,
                                                ObjectProvider<RedisConnectionFactory> connectionFactory,
                                                ApplicationAvailability applicationAvailability,
                                                ApplicationEventPublisher eventPublisher) {
        return new RedisReadOnlyRecovery(properties.getRecovery(), writeProbe, connectionFactory, applicationAvailability, eventPublisher);
    }

}
