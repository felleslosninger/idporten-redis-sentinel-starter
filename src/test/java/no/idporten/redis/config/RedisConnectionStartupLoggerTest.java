package no.idporten.redis.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.lettuce.core.ReadFrom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("When the application is ready")
class RedisConnectionStartupLoggerTest {

    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RedisConnectionStartupLogger.class);

    @Mock
    private ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;

    @BeforeEach
    void captureLog() {
        logEvents.start();
        logger.addAppender(logEvents);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(logEvents);
    }

    private static LettuceConnectionFactory sentinelFactory(LettuceClientConfiguration clientConfiguration) {
        // Not started: the factory is only inspected, never connected
        return new LettuceConnectionFactory(new RedisSentinelConfiguration("mymaster", Set.of("sentinel:26379")), clientConfiguration);
    }

    @Test
    @DisplayName("then Sentinel with read-from is logged as Master/Replica topology")
    void logsMasterReplicaMode() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(sentinelFactory(LettuceClientConfiguration.builder().readFrom(ReadFrom.UPSTREAM_PREFERRED).build()));

        new RedisConnectionStartupLogger(connectionFactoryProvider).logConnectionMode();

        assertThat(messages(Level.INFO)).singleElement().asString()
                .contains("sentinel=true")
                .contains("readFrom=ReadFromUpstreamPreferred")
                .contains("masterReplicaTopology=true")
                .contains("sharedNativeConnection=true");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("then Sentinel without read-from is logged with a warning")
    void warnsOnSentinelWithoutReadFrom() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(sentinelFactory(LettuceClientConfiguration.defaultConfiguration()));

        new RedisConnectionStartupLogger(connectionFactoryProvider).logConnectionMode();

        assertThat(messages(Level.INFO)).singleElement().asString()
                .contains("sentinel=true")
                .contains("readFrom=none")
                .contains("masterReplicaTopology=false");
        assertThat(messages(Level.WARN)).singleElement().asString().contains("read-from");
    }

    @Test
    @DisplayName("then a standalone factory is logged without a warning")
    void standaloneIsNotWarnedAbout() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379), LettuceClientConfiguration.defaultConfiguration()));

        new RedisConnectionStartupLogger(connectionFactoryProvider).logConnectionMode();

        assertThat(messages(Level.INFO)).singleElement().asString().contains("sentinel=false");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("then a missing factory is logged, not failed on")
    void missingFactoryIsLogged() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(null);

        new RedisConnectionStartupLogger(connectionFactoryProvider).logConnectionMode();

        assertThat(messages(Level.INFO)).singleElement().asString().contains("no LettuceConnectionFactory");
    }

    private List<String> messages(Level level) {
        return logEvents.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

}
