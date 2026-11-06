package no.idporten.redis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for the starter. Everything is on by default, so an application only needs these to turn something off or
 * to tune the recovery.
 */
@ConfigurationProperties(prefix = "idporten.redis-sentinel")
public class RedisSentinelProperties {

    /**
     * Apply {@code read-from: upstream-preferred} when Sentinel is configured and {@code spring.data.redis.lettuce.read-from}
     * is not set. Without a read-from, Spring Data opens a plain connection that only re-resolves the master when the
     * TCP connection dies, so a node demoted while the connection stays open answers READONLY until the connection is
     * reset.
     */
    private boolean defaultReadFrom = true;

    private final Recovery recovery = new Recovery();

    public boolean isDefaultReadFrom() {
        return defaultReadFrom;
    }

    public void setDefaultReadFrom(boolean defaultReadFrom) {
        this.defaultReadFrom = defaultReadFrom;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    public static class Recovery {

        /**
         * Whether READONLY detection and recovery is active.
         */
        private boolean enabled = true;

        /**
         * How often a short-lived key is written to check that the connected node accepts writes. Zero runs the probe
         * once, when the application first accepts traffic.
         */
        private Duration probeInterval = Duration.ofSeconds(5);

        /**
         * Number of reset-and-probe attempts answered with READONLY before liveness is set to BROKEN.
         */
        private int maxAttempts = 10;

        /**
         * Wait between recovery attempts.
         */
        private Duration retryInterval = Duration.ofSeconds(2);

        /**
         * How long Redis may stay unreachable during recovery before liveness is set to BROKEN. Unreachable does not
         * count towards max-attempts, so without this limit a pod stuck on a broken connection would never be restarted.
         */
        private Duration maxUnavailableDuration = Duration.ofMinutes(5);

        /**
         * After a successful recovery, new READONLY answers are ignored for this long.
         */
        private Duration cooldown = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getProbeInterval() {
            return probeInterval;
        }

        public void setProbeInterval(Duration probeInterval) {
            this.probeInterval = probeInterval;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getRetryInterval() {
            return retryInterval;
        }

        public void setRetryInterval(Duration retryInterval) {
            this.retryInterval = retryInterval;
        }

        public Duration getMaxUnavailableDuration() {
            return maxUnavailableDuration;
        }

        public void setMaxUnavailableDuration(Duration maxUnavailableDuration) {
            this.maxUnavailableDuration = maxUnavailableDuration;
        }

        public Duration getCooldown() {
            return cooldown;
        }

        public void setCooldown(Duration cooldown) {
            this.cooldown = cooldown;
        }

    }

}
