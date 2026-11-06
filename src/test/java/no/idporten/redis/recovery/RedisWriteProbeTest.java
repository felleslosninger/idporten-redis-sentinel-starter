package no.idporten.redis.recovery;

import io.lettuce.core.RedisReadOnlyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.types.Expiration;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("When probing whether Redis accepts writes")
class RedisWriteProbeTest {

    private static final String PROBE_KEY_STRING = "demo:redis-sentinel:write-probe";
    private static final byte[] PROBE_KEY = PROBE_KEY_STRING.getBytes(StandardCharsets.UTF_8);

    @Mock
    private ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;
    @Mock
    private RedisConnectionFactory connectionFactory;
    @Mock
    private RedisConnection connection;
    @Mock
    private RedisStringCommands stringCommands;

    private RedisWriteProbe probe;

    @BeforeEach
    void setUp() {
        probe = new RedisWriteProbe(connectionFactoryProvider, PROBE_KEY_STRING);
    }

    private void connectionAvailable() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.stringCommands()).thenReturn(stringCommands);
    }

    @Test
    @DisplayName("then a successful write is WRITABLE, uses a short-lived key, and closes the connection")
    void successfulWriteIsWritable() {
        connectionAvailable();
        when(stringCommands.set(eq(PROBE_KEY), any(byte[].class), any(Expiration.class), eq(SetOption.UPSERT))).thenReturn(true);

        assertThat(probe.probe()).isEqualTo(RedisWriteProbe.Result.WRITABLE);

        var expiration = ArgumentCaptor.forClass(Expiration.class);
        verify(stringCommands).set(eq(PROBE_KEY), any(byte[].class), expiration.capture(), eq(SetOption.UPSERT));
        assertThat(expiration.getValue().getExpirationTimeInSeconds()).isEqualTo(RedisWriteProbe.PROBE_KEY_TTL_SECONDS);
        verify(connection).close();
    }

    @Test
    @DisplayName("then a READONLY answer is READ_ONLY and the connection is closed")
    void readOnlyAnswerIsReadOnly() {
        connectionAvailable();
        when(stringCommands.set(eq(PROBE_KEY), any(byte[].class), any(Expiration.class), eq(SetOption.UPSERT)))
                .thenThrow(new RedisSystemException("Error in execution", new RedisReadOnlyException("READONLY You can't write against a read only replica.")));

        assertThat(probe.probe()).isEqualTo(RedisWriteProbe.Result.READ_ONLY);

        verify(connection).close();
    }

    @Test
    @DisplayName("then any other failure is UNAVAILABLE and the connection is closed")
    void otherFailureIsUnavailable() {
        connectionAvailable();
        when(stringCommands.set(eq(PROBE_KEY), any(byte[].class), any(Expiration.class), eq(SetOption.UPSERT)))
                .thenThrow(new RedisSystemException("Error in execution", new RuntimeException("NOREPLICAS Not enough good replicas to write.")));

        assertThat(probe.probe()).isEqualTo(RedisWriteProbe.Result.UNAVAILABLE);

        verify(connection).close();
    }

    @Test
    @DisplayName("then failing to get a connection is UNAVAILABLE")
    void noConnectionIsUnavailable() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertThat(probe.probe()).isEqualTo(RedisWriteProbe.Result.UNAVAILABLE);
    }

    @Test
    @DisplayName("then there is nothing to check without a Redis connection factory")
    void missingFactoryIsWritable() {
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(null);

        assertThat(probe.probe()).isEqualTo(RedisWriteProbe.Result.WRITABLE);

        verifyNoInteractions(connectionFactory);
    }

}
