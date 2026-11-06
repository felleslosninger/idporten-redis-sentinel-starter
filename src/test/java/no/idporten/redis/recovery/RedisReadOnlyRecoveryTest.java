package no.idporten.redis.recovery;

import io.lettuce.core.RedisReadOnlyException;
import no.idporten.redis.config.RedisSentinelProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.AvailabilityState;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static no.idporten.redis.recovery.RedisWriteProbe.Result.READ_ONLY;
import static no.idporten.redis.recovery.RedisWriteProbe.Result.UNAVAILABLE;
import static no.idporten.redis.recovery.RedisWriteProbe.Result.WRITABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("When Redis answers READONLY")
class RedisReadOnlyRecoveryTest {

    @Mock
    private RedisWriteProbe writeProbe;
    @Mock
    private ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;
    @Mock
    private ApplicationAvailability applicationAvailability;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private LettuceConnectionFactory connectionFactory;
    private RedisSentinelProperties.Recovery properties;
    private RedisReadOnlyRecovery recovery;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(LettuceConnectionFactory.class);
        lenient().when(connectionFactoryProvider.getIfAvailable()).thenReturn(connectionFactory);
        lenient().when(applicationAvailability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        properties = new RedisSentinelProperties.Recovery();
        properties.setMaxAttempts(3);
        properties.setRetryInterval(Duration.ZERO);
        properties.setCooldown(Duration.ofMinutes(1));
        properties.setProbeInterval(Duration.ofHours(1)); // only the first, immediate run unless a test says otherwise
        recovery = new RedisReadOnlyRecovery(properties, writeProbe, connectionFactoryProvider, applicationAvailability, eventPublisher);
    }

    @AfterEach
    void tearDown() {
        recovery.shutdown();
    }

    @Test
    @DisplayName("then a READONLY cause inside a RedisSystemException is recognized")
    void readOnlyIsFoundInCauseChain() {
        var readOnly = new RedisSystemException("Error in execution", new RedisReadOnlyException("READONLY You can't write against a read only replica."));

        assertThat(RedisWriteProbe.isReadOnly(readOnly)).isTrue();
        assertThat(RedisWriteProbe.isReadOnly(new RedisSystemException("Error in execution", new RuntimeException("NOREPLICAS")))).isFalse();
    }

    @Test
    @DisplayName("then readiness goes down, the connection is reset, and readiness comes back when the probe succeeds")
    void recoversWhenProbeSucceeds() {
        when(writeProbe.probe()).thenReturn(UNAVAILABLE, WRITABLE);

        recovery.onReadOnly();

        verify(eventPublisher, timeout(2000).times(2)).publishEvent(any(AvailabilityChangeEvent.class));
        assertThat(publishedStates()).containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
        verify(connectionFactory, times(2)).resetConnection();
    }

    @Test
    @DisplayName("then an unreachable Redis does not set liveness to BROKEN within max-unavailable-duration")
    void unavailableRedisDoesNotBreakLivenessWithinLimit() {
        when(writeProbe.probe()).thenReturn(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, WRITABLE);

        recovery.onReadOnly();

        verify(eventPublisher, timeout(2000).times(2)).publishEvent(any(AvailabilityChangeEvent.class));
        assertThat(publishedStates()).containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
        verify(connectionFactory, times(6)).resetConnection();
    }

    @Test
    @DisplayName("then liveness is set to BROKEN when Redis stays unreachable for max-unavailable-duration")
    void brokenWhenUnavailableForTooLong() {
        properties.setRetryInterval(Duration.ofMillis(20));
        properties.setMaxUnavailableDuration(Duration.ofMillis(100));
        when(writeProbe.probe()).thenReturn(UNAVAILABLE);

        recovery.onReadOnly();

        verify(eventPublisher, timeout(5000).times(2)).publishEvent(any(AvailabilityChangeEvent.class));
        assertThat(publishedStates()).containsExactly(ReadinessState.REFUSING_TRAFFIC, LivenessState.BROKEN);
    }

    @Test
    @DisplayName("then only READONLY answers count towards the attempt limit")
    void onlyReadOnlyAnswersCount() {
        when(writeProbe.probe()).thenReturn(UNAVAILABLE, READ_ONLY, UNAVAILABLE, READ_ONLY, READ_ONLY);

        recovery.onReadOnly();

        verify(eventPublisher, timeout(2000).times(2)).publishEvent(any(AvailabilityChangeEvent.class));
        assertThat(publishedStates()).containsExactly(ReadinessState.REFUSING_TRAFFIC, LivenessState.BROKEN);
        verify(connectionFactory, times(5)).resetConnection();
    }

    @Test
    @DisplayName("then liveness is set to BROKEN when the probe keeps failing")
    void liveBrokenWhenProbeKeepsFailing() {
        when(writeProbe.probe()).thenReturn(READ_ONLY);

        recovery.onReadOnly();

        verify(eventPublisher, timeout(2000).times(2)).publishEvent(any(AvailabilityChangeEvent.class));
        assertThat(publishedStates()).containsExactly(ReadinessState.REFUSING_TRAFFIC, LivenessState.BROKEN);
        verify(connectionFactory, times(3)).resetConnection();
    }

    @Test
    @DisplayName("then a refusal that was not ours is left alone")
    void doesNotOverrideOtherRefusal() throws InterruptedException {
        lenient().when(applicationAvailability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        when(writeProbe.probe()).thenReturn(WRITABLE);

        recovery.onReadOnly();
        awaitRecoveryFinished();

        verify(eventPublisher, never()).publishEvent(any(AvailabilityChangeEvent.class));
    }

    @Test
    @DisplayName("then a refusal taken over by someone else, e.g. graceful shutdown, is not undone")
    void doesNotUndoRefusalTakenOver() throws InterruptedException {
        when(writeProbe.probe()).thenAnswer(invocation -> {
            // someone else, e.g. graceful shutdown, refuses traffic while we are still recovering
            recovery.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
            return WRITABLE;
        });

        recovery.onReadOnly();
        awaitRecoveryFinished();

        assertThat(publishedStates()).containsExactly(ReadinessState.REFUSING_TRAFFIC);
    }

    @Test
    @DisplayName("then new READONLY errors within the cooldown are ignored")
    void ignoresReadOnlyDuringCooldown() throws InterruptedException {
        when(writeProbe.probe()).thenReturn(WRITABLE);
        recovery.onReadOnly();
        awaitRecoveryFinished();

        recovery.onReadOnly();
        awaitRecoveryFinished(); // a recovery wrongly started by the call above would have run by now

        verify(connectionFactory, times(1)).resetConnection();
        verify(eventPublisher, times(2)).publishEvent(any(AvailabilityChangeEvent.class));
    }

    @Test
    @DisplayName("then nothing happens when recovery is disabled")
    void disabledDoesNothing() {
        properties.setEnabled(false);

        recovery.onReadOnly();
        recovery.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));

        verifyNoInteractions(writeProbe, connectionFactory, eventPublisher);
    }

    @Test
    @DisplayName("then a read-only node at startup starts recovery")
    void startupProbeStartsRecovery() {
        when(writeProbe.probe()).thenReturn(READ_ONLY, WRITABLE);

        recovery.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));

        verify(connectionFactory, timeout(2000)).resetConnection();
    }

    @Test
    @DisplayName("then a writable node at startup does nothing")
    void startupProbeWritableDoesNothing() {
        when(writeProbe.probe()).thenReturn(WRITABLE);

        recovery.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));

        verify(writeProbe, timeout(2000)).probe();
        verify(connectionFactory, never()).resetConnection();
        verify(eventPublisher, never()).publishEvent(any(AvailabilityChangeEvent.class));
    }

    @Test
    @DisplayName("then the periodic probe detects a node that becomes read-only later")
    void periodicProbeDetectsReadOnly() {
        properties.setProbeInterval(Duration.ofMillis(20));
        when(writeProbe.probe()).thenReturn(WRITABLE, WRITABLE, READ_ONLY, WRITABLE);

        recovery.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));

        verify(connectionFactory, timeout(2000)).resetConnection();
        verify(eventPublisher, timeout(2000).times(2)).publishEvent(any(AvailabilityChangeEvent.class));
        assertThat(publishedStates()).containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    @DisplayName("then the periodic probe keeps running after a probe failure and is only scheduled once")
    void periodicProbeKeepsRunning() {
        properties.setProbeInterval(Duration.ofMillis(10));
        when(writeProbe.probe()).thenThrow(new IllegalStateException("boom")).thenReturn(WRITABLE);

        recovery.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        recovery.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));

        verify(writeProbe, timeout(2000).atLeast(3)).probe();
        verify(connectionFactory, never()).resetConnection();
    }

    @Test
    @DisplayName("then no new recovery starts once it has given up and liveness is BROKEN")
    void noRecoveryAfterGivingUp() throws InterruptedException {
        when(writeProbe.probe()).thenReturn(READ_ONLY);
        recovery.onReadOnly();
        verify(eventPublisher, timeout(2000).times(2)).publishEvent(any(AvailabilityChangeEvent.class));

        recovery.onReadOnly();
        awaitRecoveryFinished(); // a recovery wrongly started by the call above would have run by now

        verify(connectionFactory, times(3)).resetConnection();
        verify(eventPublisher, times(2)).publishEvent(any(AvailabilityChangeEvent.class));
    }

    @Test
    @DisplayName("then readiness is restored and the error is rethrown when recovery cannot be started")
    void restoresReadinessWhenRecoveryCannotStart() {
        recovery.shutdown();

        assertThatThrownBy(() -> recovery.onReadOnly()).isInstanceOf(RejectedExecutionException.class);

        assertThat(publishedStates()).containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(recovery.isRecovering()).isFalse();
    }

    @Test
    @DisplayName("then a readiness event while the context is closing does not fail")
    void readinessEventAfterShutdownDoesNotFail() {
        recovery.shutdown();

        assertThatCode(() -> recovery.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC)))
                .doesNotThrowAnyException();
        verifyNoInteractions(writeProbe);
    }

    @Test
    @DisplayName("then readiness events published by the recovery itself are ignored")
    void ignoresOwnEvents() {
        recovery.onReadinessChange(new AvailabilityChangeEvent<>(recovery, ReadinessState.ACCEPTING_TRAFFIC));

        verifyNoInteractions(writeProbe, eventPublisher);
    }

    private void awaitRecoveryFinished() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (recovery.isRecovering() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(recovery.isRecovering()).as("recovery finished").isFalse();
    }

    private List<AvailabilityState> publishedStates() {
        var captor = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream().map(event -> (AvailabilityState) event.getState()).toList();
    }

}
