package no.idporten.redis.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("When the application has started")
class RedisConnectionWarmupTest {

    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RedisConnectionWarmup.class);

    @Mock
    private ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;

    @Mock
    private LettuceConnectionFactory connectionFactory;

    @BeforeEach
    void captureLog() {
        logEvents.start();
        logger.addAppender(logEvents);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(logEvents);
    }

    @Test
    @DisplayName("then both shared connections are initialized")
    void initializesSharedConnections() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(connectionFactory);
        when(connectionFactory.getShareNativeConnection()).thenReturn(true);
        when(connectionFactory.isRunning()).thenReturn(true);

        new RedisConnectionWarmup(connectionFactoryProvider).warmUp();

        verify(connectionFactory).validateConnection();
        assertThat(messages(Level.INFO)).singleElement().asString().contains("shared connections ready");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("then an unreachable Redis is logged as a warning and the application keeps starting")
    void failureIsLoggedNotThrown() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(connectionFactory);
        when(connectionFactory.getShareNativeConnection()).thenReturn(true);
        when(connectionFactory.isRunning()).thenReturn(true);
        doThrow(new RedisConnectionFailureException("Unable to connect to sentinel:26379")).when(connectionFactory).validateConnection();

        assertThatCode(() -> new RedisConnectionWarmup(connectionFactoryProvider).warmUp()).doesNotThrowAnyException();

        assertThat(messages(Level.WARN)).singleElement().asString()
                .contains("warm-up failed")
                .contains("RedisConnectionFailureException")
                .contains("Unable to connect to sentinel:26379");
    }

    @Test
    @DisplayName("then a factory without a shared native connection is left alone")
    void skipsWithoutSharedConnection() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(connectionFactory);
        when(connectionFactory.getShareNativeConnection()).thenReturn(false);

        new RedisConnectionWarmup(connectionFactoryProvider).warmUp();

        verify(connectionFactory, never()).validateConnection();
        assertThat(messages(Level.INFO)).singleElement().asString().contains("skipped");
    }

    @Test
    @DisplayName("then a factory that is not started is left alone")
    void skipsWhenNotStarted() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(connectionFactory);
        when(connectionFactory.getShareNativeConnection()).thenReturn(true);
        when(connectionFactory.isRunning()).thenReturn(false);

        new RedisConnectionWarmup(connectionFactoryProvider).warmUp();

        verify(connectionFactory, never()).validateConnection();
        assertThat(messages(Level.INFO)).isEmpty();
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("then a missing factory is skipped quietly")
    void skipsWithoutFactory() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> new RedisConnectionWarmup(connectionFactoryProvider).warmUp()).doesNotThrowAnyException();

        assertThat(messages(Level.INFO)).isEmpty();
        assertThat(messages(Level.WARN)).isEmpty();
    }

    private List<String> messages(Level level) {
        return logEvents.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

}
