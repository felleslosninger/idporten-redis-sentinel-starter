package no.idporten.redis.recovery;

import io.lettuce.core.RedisReadOnlyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.SetCondition;
import org.springframework.data.redis.core.types.Expiration;

import java.nio.charset.StandardCharsets;

/**
 * Checks that the node we are connected to accepts writes. PING and INFO also succeed on a read-only replica, so the
 * built-in redis health indicator cannot tell that we are connected to a demoted master.
 */
public class RedisWriteProbe {

    private static final Logger log = LoggerFactory.getLogger(RedisWriteProbe.class);

    static final long PROBE_KEY_TTL_SECONDS = 10;

    public enum Result {
        WRITABLE,
        READ_ONLY,
        UNAVAILABLE
    }

    private final ObjectProvider<RedisConnectionFactory> connectionFactory;
    private final byte[] probeKey;

    public RedisWriteProbe(ObjectProvider<RedisConnectionFactory> connectionFactory, String probeKey) {
        this.connectionFactory = connectionFactory;
        this.probeKey = probeKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes a short-lived key. Never throws.
     *
     * @return WRITABLE if the write succeeded or there is no Redis connection factory to check,
     * READ_ONLY if Redis answered READONLY, UNAVAILABLE for any other failure
     */
    public Result probe() {
        RedisConnectionFactory factory = connectionFactory.getIfAvailable();
        if (factory == null) {
            return Result.WRITABLE;
        }
        try (RedisConnection connection = factory.getConnection()) {
            connection.stringCommands().set(probeKey, new byte[]{1}, SetCondition.upsert(), Expiration.seconds(PROBE_KEY_TTL_SECONDS));
            return Result.WRITABLE;
        } catch (RuntimeException e) {
            if (isReadOnly(e)) {
                return Result.READ_ONLY;
            }
            // The message can contain Redis or Sentinel host names and ports, so it is only logged at debug level.
            log.debug("Redis write probe failed: {}: {}", NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName(), e.getMessage());
            return Result.UNAVAILABLE;
        }
    }

    /**
     * Spring wraps Lettuce's RedisReadOnlyException in a RedisSystemException, so look through the cause chain.
     * Applications that catch Redis exceptions themselves can use this to decide whether to call
     * {@link RedisReadOnlyRecovery#onReadOnly()}.
     */
    public static boolean isReadOnly(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisReadOnlyException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

}
