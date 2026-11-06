package no.idporten.redis.config;

import io.lettuce.core.ReadFrom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.LettuceClientConfigurationBuilder;

/**
 * Makes the auto-configured Lettuce factory open a Sentinel-aware Master/Replica connection, which follows
 * {@code +switch-master} instead of waiting for the old master's TCP connection to die. Only registered when Sentinel
 * is configured and {@code spring.data.redis.lettuce.read-from} is not, see
 * {@link no.idporten.redis.autoconfigure.RedisSentinelReadFromAutoConfiguration}.
 */
public class SentinelReadFromDefaultCustomizer implements LettuceClientConfigurationBuilderCustomizer {

    private static final Logger log = LoggerFactory.getLogger(SentinelReadFromDefaultCustomizer.class);

    @Override
    public void customize(LettuceClientConfigurationBuilder builder) {
        builder.readFrom(ReadFrom.UPSTREAM_PREFERRED);
        log.info("Redis Sentinel is configured without spring.data.redis.lettuce.read-from, defaulting to upstream-preferred");
    }

}
