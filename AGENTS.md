# AGENTS.md

Context for anyone, human or agent, changing this repo. The README says what the starter does; this file says why
it is the way it is and what we learned getting here. Keep it short and update it when a decision changes.

## What this is

A Spring Boot 4 starter (Java 25, Lettuce) for applications that use Redis behind Sentinel. It (1) defaults
`read-from` to `upstream-preferred` so Lettuce opens a Sentinel-aware Master/Replica connection, (2) logs the
effective connection mode at startup, (3) detects a connection stuck on a demoted master (READONLY) with a periodic
write probe, and (4) recovers by refusing traffic, resetting the Lettuce connection and probing again, or setting
liveness to BROKEN so the pod is restarted.

Origin: idporten-login ID-7167 carried the first version of the recovery (`no.idporten.integration.redis.recovery`).
ID-7168 lifted it into this starter so ~30 Sentinel-backed apps in idporten-cd get it from one dependency.

## Why it exists (the incident)

During OpenShift node patching on 2026-11-05/06, pods in several apps kept writing to a Redis node that had been
demoted to replica. Every write failed with `RedisReadOnlyException: READONLY` until the pod was restarted. All pods
ran the newest images with `read-from: upstream-preferred` already set, so Master/Replica mode alone did not prevent
it. The explanation of how a client can miss Sentinel's master switch is written up in the idporten-login README
section "Redis READONLY recovery" (branch ID-7167); do not re-derive it here.

## Facts we verified, so you do not have to

- `PING` and `INFO` succeed on a replica. The built-in `redis` health indicator therefore stays UP on a demoted node.
  Only a write tells the truth, hence the write probe.
- Lettuce's read-only error reaches Spring code as `RedisSystemException("Error in execution")` with
  `io.lettuce.core.RedisReadOnlyException` as the cause. `RedisWriteProbe.isReadOnly` walks the cause chain.
- `LettuceConnectionFactory.resetConnection()` closes the shared native connection; the next `getConnection()`
  resolves the master through Sentinel again. In-flight requests on the old connection fail once.
- Spring Boot 4.1 maps `spring.data.redis.lettuce.read-from` onto the auto-configured factory and applies
  `LettuceClientConfigurationBuilderCustomizer` beans. Customizer package in Boot 4:
  `org.springframework.boot.data.redis.autoconfigure`. Boot 3 has it elsewhere, which is why this starter is Boot 4
  only.
- With Sentinel configured but no read-from, Spring Data opens a plain connection via `RedisClient.connect` that only
  re-resolves the master when the TCP connection drops. With read-from it uses `MasterReplica.connect`.
- Sentinel settings for the apps live in idporten-cd, `apps/<product>/<env>/base/redis-sentinel-spring`, loaded via
  the `redis-spring` Spring profile. This starter adds no environment config; it only supplies a safe default.
- All Sentinel-backed apps in idporten-cd use the platform's shared probe component, which hits Boot's
  `/health/readiness` and `/health/liveness` on port 8090. Readiness and liveness changes made by this starter
  therefore reach Kubernetes without any deployment change.

## Decisions

- **Periodic write probe as the generic trigger**, not an aspect or a connection-factory proxy. It works for every
  app regardless of how it uses Redis (sessions, caches, RedisTemplate), also for idle pods, and costs one SET per
  pod per interval. Apps that catch the error themselves can call `RedisReadOnlyRecovery.onReadOnly()` for an
  immediate reaction; the probe is the safety net.
- **Readiness first, liveness last.** A stuck pod is taken out of rotation at once; it is only restarted after
  `max-attempts` READONLY answers or `max-unavailable-duration` without an answer. Unreachable Redis does not count
  as READONLY, so a short outage does not restart every pod at the same time.
- **Properties under `idporten.redis-sentinel.*`**, not the ID-7167 prefix `idporten.redis-recovery.*`. The starter
  owns the read-from default too, so one prefix for the whole starter. The README documents the rename.
- **The probe stays quiet during an outage.** It logs only when its result changes, so test environments without
  Redis do not get a warning every 5 seconds. Disable it in unit tests with `idporten.redis-sentinel.recovery.enabled=false`.
- **Explicit read-from always wins.** The customizer is only registered when Sentinel is configured and
  `spring.data.redis.lettuce.read-from` is unset (`SentinelWithoutReadFromCondition`), and it runs before Boot's
  Redis auto-configuration.
- **Java 25**, matching the platform default in the shared workflows and where the apps are heading. Apps still on
  Java 21 cannot consume the starter until they upgrade.
- **No `edited` trigger on the full PR workflow.** The shared docs include it so title changes rerun the title check,
  but with a PR checklist every ticked box is an edit and would rerun the full build. `pr-check-checklist.yml` runs
  on `edited` instead. Same pattern as idporten-login.
- **Golden-path workflows** from felleslosninger/github-workflows: `ci-pr-checks-lib.yml` and
  `ci-build-publish-lib.yml`. The Maven workflows the older starters call (`ci-maven-build-lib.yml`,
  `ci-maven-deploy.yml`) are archived; do not copy them from another repo. Deviations are listed in
  `.github/golden-path.md`.

## Conventions

- PR titles in Norwegian with the Jira key: `ID-XXXX: ...`. The PR template is the library variant used by the
  other idporten libs; only the owner ticks the owner checklist, the reviewer section is for reviewers.
- Release by creating a GitHub release with the version as tag; `release.yml` publishes to GitHub Packages.
- Tests: `mvn verify`. Unit tests mock `LettuceConnectionFactory` (needs the Mockito Java agent configured in the
  pom). The auto-configuration test uses `ApplicationContextRunner` and must register `ApplicationAvailabilityBean`
  itself, since only `SpringApplication` registers it in a real app.

## Not covered, on purpose

- Spring Boot 3 apps, Quarkus apps (different Redis client), Jedis (connection cannot be reset).
- Fixing the Redis/Sentinel side (redis-ha chart, failover procedure during patching). That belongs in idporten-cd.
