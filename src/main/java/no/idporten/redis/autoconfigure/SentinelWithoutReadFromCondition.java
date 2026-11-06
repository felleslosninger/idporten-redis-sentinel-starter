package no.idporten.redis.autoconfigure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Matches when Redis Sentinel is configured and no read-from is, i.e. when the auto-configured Lettuce factory would
 * open a plain connection that does not follow Sentinel's master switch.
 */
class SentinelWithoutReadFromCondition implements Condition {

    static final String SENTINEL_MASTER = "spring.data.redis.sentinel.master";
    static final String READ_FROM = "spring.data.redis.lettuce.read-from";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        return StringUtils.hasText(environment.getProperty(SENTINEL_MASTER))
                && !StringUtils.hasText(environment.getProperty(READ_FROM));
    }

}
