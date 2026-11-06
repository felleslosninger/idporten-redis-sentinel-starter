# idporten-redis-sentinel-starter

Spring Boot starter that keeps a Lettuce client on the Redis Sentinel master and recovers when a pod ends up writing to
a node that has been demoted to replica (`READONLY You can't write against a read only replica`).

It replaces the per-application copies of this logic, so every ID-porten application that uses Redis behind Sentinel
gets the same behaviour by adding one dependency.

## What it does

1. **Defaults `read-from` to `upstream-preferred` when Sentinel is configured.** Without a read-from, Spring Data opens
   a plain connection that only re-resolves the master when the TCP connection dies. With it, Lettuce opens a
   Sentinel-aware Master/Replica connection that follows `+switch-master`. An explicit
   `spring.data.redis.lettuce.read-from` is always left alone.
2. **Logs the effective connection mode on startup**, so the pod log shows whether Sentinel and Master/Replica
   topology are in effect, and warns if Sentinel is configured without a read-from:
   ```
   Redis connection: sentinel=true, readFrom=ReadFromUpstreamPreferred, masterReplicaTopology=true, sharedNativeConnection=true
   ```
3. **Detects READONLY with a periodic write probe.** Every `probe-interval` a short-lived key
   (`<spring.application.name>:redis-sentinel:write-probe`, 10 s TTL) is written. `PING` and `INFO` also succeed on a
   replica, so the built-in `redis` health indicator cannot tell that the connected node is read-only. The first probe
   runs as soon as the application accepts traffic, because a pod that starts during a failover can connect to the old
   master from the beginning.
4. **Recovers.** On READONLY the pod refuses traffic (readiness `OUT_OF_SERVICE`), the Lettuce connection is reset so the
   master is looked up again through Sentinel, and the write probe verifies the result. When the probe succeeds the
   pod accepts traffic again, but only if it was this recovery that refused it. After `max-attempts` READONLY answers
   liveness is set to `BROKEN` so the pod is restarted. If Redis is unreachable instead, the pod keeps refusing traffic
   and keeps trying, so a short outage does not restart every pod at once; after `max-unavailable-duration` liveness is
   set to `BROKEN` too, so a pod stuck on a connection a reset cannot fix is eventually restarted.

Readiness and liveness changes reach Kubernetes through Spring Boot's `/health/readiness` and `/health/liveness`
groups, which the platform's shared probe component already uses. Make sure the probes are enabled:

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true   # on by default when running on Kubernetes
```

## Requirements

* Spring Boot 4 with `spring-boot-starter-data-redis` (Lettuce, the default client)
* Java 25

The starter does nothing unless a `RedisConnectionFactory` bean exists, so it is safe on the classpath of an
application that only uses Redis in some profiles.

## Usage

```xml
<dependency>
    <groupId>no.idporten.redis</groupId>
    <artifactId>idporten-redis-sentinel-starter</artifactId>
    <version>...</version>
</dependency>
```

Sentinel itself is configured as before, typically from the shared `redis-sentinel-spring` config in the `*-cd` repo:

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: redis-ha-server-0.redis-ha:26379,redis-ha-server-1.redis-ha:26379,redis-ha-server-2.redis-ha:26379
        password: ${REDIS_PASSWORD}
      password: ${REDIS_PASSWORD}
      # lettuce.read-from is optional: the starter defaults it to upstream-preferred
```

### Reacting at once from application code

The periodic probe detects READONLY within one `probe-interval`. An application that catches the Redis exception
itself can start recovery immediately instead of waiting for the next probe:

```java
} catch (RedisSystemException ex) {
    if (RedisWriteProbe.isReadOnly(ex)) {
        redisReadOnlyRecovery.onReadOnly(); // non-blocking, safe to call from a request thread
    }
    throw ex;
}
```

The request that hit READONLY still fails; recovery runs in the background.

## Properties

| Key                                                      | Default | Description                                                                                 |
|----------------------------------------------------------|---------|---------------------------------------------------------------------------------------------|
| `idporten.redis-sentinel.default-read-from`              | `true`  | Default `read-from` to `upstream-preferred` when Sentinel is configured without one         |
| `idporten.redis-sentinel.recovery.enabled`               | `true`  | READONLY detection and recovery                                                             |
| `idporten.redis-sentinel.recovery.probe-interval`        | `5s`    | How often the write probe runs; `0` runs it once, when the application first accepts traffic |
| `idporten.redis-sentinel.recovery.max-attempts`          | `10`    | READONLY answers during recovery before liveness is set to `BROKEN`                         |
| `idporten.redis-sentinel.recovery.retry-interval`        | `2s`    | Wait between recovery attempts                                                              |
| `idporten.redis-sentinel.recovery.max-unavailable-duration` | `5m` | How long Redis may stay unreachable during recovery before liveness is set to `BROKEN`      |
| `idporten.redis-sentinel.recovery.cooldown`              | `10s`   | READONLY answers are ignored for this long after a successful recovery                      |

In unit tests without a Redis server, set `idporten.redis-sentinel.recovery.enabled: false` to avoid probe warnings.
The startup log line stays.

## Migrating from an application-local copy

idporten-login carried the first version of this under `no.idporten.integration.redis.recovery` (ID-7167). To switch:

1. Add the dependency and delete the local `RedisReadOnlyRecovery`, `RedisWriteProbe` and `RedisRecoveryProperties`
   with their tests.
2. Rename properties from `idporten.redis-recovery.*` to `idporten.redis-sentinel.recovery.*`.
3. Keep calling `redisReadOnlyRecovery.onReadOnly()` from the application's own exception handling if it has one, now
   importing the class from `no.idporten.redis.recovery`.

## Not covered

* Spring Boot 3 applications: the recovery classes compile against Boot 3, but the read-from customizer uses the
  Boot 4 package. Upgrade the application, or build a Boot 3 variant from this repo if one is needed.
* Quarkus applications, which use a different Redis client.
* Jedis: the connection cannot be reset, so the starter only logs and probes.

## Building

```
mvn verify
```
