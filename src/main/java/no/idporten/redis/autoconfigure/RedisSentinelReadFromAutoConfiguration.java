package no.idporten.redis.autoconfigure;

import no.idporten.redis.config.RedisSentinelProperties;
import no.idporten.redis.config.SentinelReadFromDefaultCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Registers the read-from default before Boot creates the Lettuce factory, so a Sentinel setup without an explicit
 * {@code spring.data.redis.lettuce.read-from} still gets a Master/Replica connection. An explicit read-from, or
 * {@code idporten.redis-sentinel.default-read-from=false}, switches this off.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass({LettuceConnectionFactory.class, LettuceClientConfigurationBuilderCustomizer.class})
@ConditionalOnProperty(prefix = "idporten.redis-sentinel", name = "default-read-from", havingValue = "true", matchIfMissing = true)
@Conditional(SentinelWithoutReadFromCondition.class)
@EnableConfigurationProperties(RedisSentinelProperties.class)
public class RedisSentinelReadFromAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SentinelReadFromDefaultCustomizer sentinelReadFromDefaultCustomizer() {
        return new SentinelReadFromDefaultCustomizer();
    }

}
