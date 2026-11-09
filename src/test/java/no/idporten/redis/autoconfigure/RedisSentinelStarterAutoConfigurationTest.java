package no.idporten.redis.autoconfigure;

import no.idporten.redis.config.RedisConnectionStartupLogger;
import no.idporten.redis.config.RedisConnectionWarmup;
import no.idporten.redis.config.SentinelReadFromDefaultCustomizer;
import no.idporten.redis.recovery.RedisReadOnlyRecovery;
import no.idporten.redis.recovery.RedisWriteProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("When the starter is on the classpath")
class RedisSentinelStarterAutoConfigurationTest {

    private static final String SENTINEL_MASTER = "spring.data.redis.sentinel.master=mymaster";
    private static final String SENTINEL_NODES = "spring.data.redis.sentinel.nodes=localhost:26379";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RedisSentinelReadFromAutoConfiguration.class,
                    DataRedisAutoConfiguration.class,
                    RedisReadOnlyRecoveryAutoConfiguration.class))
            // registered by SpringApplication in a real application
            .withBean(ApplicationAvailability.class, ApplicationAvailabilityBean::new)
            .withPropertyValues("spring.application.name=demo");

    private static String readFromOf(LettuceConnectionFactory factory) {
        return factory.getClientConfiguration().getReadFrom().map(r -> r.getClass().getSimpleName()).orElse("none");
    }

    @Test
    @DisplayName("then Sentinel without read-from gets upstream-preferred and the recovery beans")
    void sentinelWithoutReadFromGetsDefault() {
        runner.withPropertyValues(SENTINEL_MASTER, SENTINEL_NODES).run(context -> {
            assertThat(context).hasSingleBean(SentinelReadFromDefaultCustomizer.class)
                    .hasSingleBean(RedisReadOnlyRecovery.class)
                    .hasSingleBean(RedisWriteProbe.class)
                    .hasSingleBean(RedisConnectionStartupLogger.class)
                    .hasSingleBean(RedisConnectionWarmup.class);
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            assertThat(factory.isRedisSentinelAware()).isTrue();
            assertThat(readFromOf(factory)).isEqualTo("ReadFromUpstreamPreferred");
        });
    }

    @Test
    @DisplayName("then an explicit read-from is left alone")
    void explicitReadFromIsKept() {
        runner.withPropertyValues(SENTINEL_MASTER, SENTINEL_NODES, "spring.data.redis.lettuce.read-from=replica-preferred").run(context -> {
            assertThat(context).doesNotHaveBean(SentinelReadFromDefaultCustomizer.class);
            assertThat(readFromOf(context.getBean(LettuceConnectionFactory.class))).isEqualTo("ReadFromReplicaPreferred");
        });
    }

    @Test
    @DisplayName("then the read-from default can be switched off")
    void defaultReadFromCanBeDisabled() {
        runner.withPropertyValues(SENTINEL_MASTER, SENTINEL_NODES, "idporten.redis-sentinel.default-read-from=false").run(context -> {
            assertThat(context).doesNotHaveBean(SentinelReadFromDefaultCustomizer.class);
            assertThat(readFromOf(context.getBean(LettuceConnectionFactory.class))).isEqualTo("none");
        });
    }

    @Test
    @DisplayName("then a standalone Redis gets no read-from but still the recovery beans")
    void standaloneGetsRecoveryOnly() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(SentinelReadFromDefaultCustomizer.class)
                    .hasSingleBean(RedisReadOnlyRecovery.class);
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            assertThat(factory.isRedisSentinelAware()).isFalse();
            assertThat(readFromOf(factory)).isEqualTo("none");
        });
    }

    @Test
    @DisplayName("then the warm-up opens the shared connections when the application has started")
    void warmupRunsOnApplicationStarted() {
        LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
        when(factory.getShareNativeConnection()).thenReturn(true);
        when(factory.isRunning()).thenReturn(true);
        // A running SmartLifecycle is stopped on context close, and the lifecycle processor waits 10 s for the callback
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(factory).stop(any(Runnable.class));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisReadOnlyRecoveryAutoConfiguration.class))
                .withBean(ApplicationAvailability.class, ApplicationAvailabilityBean::new)
                .withBean(RedisConnectionFactory.class, () -> factory)
                .withPropertyValues("idporten.redis-sentinel.recovery.enabled=false")
                .run(context -> {
                    context.publishEvent(new ApplicationStartedEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
                    verify(factory).validateConnection();
                });
    }

    @Test
    @DisplayName("then the warm-up can be switched off while recovery stays")
    void warmupCanBeDisabled() {
        runner.withPropertyValues(SENTINEL_MASTER, SENTINEL_NODES, "idporten.redis-sentinel.warmup.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(RedisConnectionWarmup.class)
                    .hasSingleBean(RedisReadOnlyRecovery.class)
                    .hasSingleBean(RedisConnectionStartupLogger.class);
        });
    }

    @Test
    @DisplayName("then recovery can be switched off while the startup log stays")
    void recoveryCanBeDisabled() {
        runner.withPropertyValues(SENTINEL_MASTER, SENTINEL_NODES, "idporten.redis-sentinel.recovery.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(RedisReadOnlyRecovery.class)
                    .doesNotHaveBean(RedisWriteProbe.class)
                    .hasSingleBean(RedisConnectionStartupLogger.class)
                    .hasSingleBean(RedisConnectionWarmup.class)
                    .hasSingleBean(SentinelReadFromDefaultCustomizer.class);
        });
    }

    @Test
    @DisplayName("then nothing is registered without a Redis connection factory")
    void nothingWithoutConnectionFactory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisSentinelReadFromAutoConfiguration.class, RedisReadOnlyRecoveryAutoConfiguration.class))
                .withPropertyValues(SENTINEL_MASTER, SENTINEL_NODES)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(RedisReadOnlyRecovery.class)
                        .doesNotHaveBean(RedisConnectionStartupLogger.class)
                        .doesNotHaveBean(RedisConnectionWarmup.class)
                        // the customizer is registered regardless: it is consumed by whoever creates the factory
                        .hasSingleBean(SentinelReadFromDefaultCustomizer.class));
    }

}
