---
title: 'Observability baseline: ECS JSON logging and Prometheus metrics'
type: 'feature'
created: '2026-09-26'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
baseline_commit: 'NO_VCS'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-reactive-spring-boot-scaffold.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The service logs plain text and exposes no metrics, so log shippers cannot parse its output and nothing can scrape it.

**Approach:** Switch console logging to Spring Boot's structured ECS JSON format, and add `micrometer-registry-prometheus` (Boot-managed) so `/actuator/prometheus` is exposed on the management port and permitted anonymously there, like health and info. Every other actuator endpoint stays authenticated or unexposed. Cover it with an integration test and document it in the README. Human-readable logs for developer runs are left to the deferred `local` profile item.

</frozen-after-approval>

## Implementation Notes

- `pom.xml`: added `io.micrometer:micrometer-registry-prometheus` with no version (Boot-managed). The actuator starter in Boot 4.1.1 already brings in `spring-boot-starter-micrometer-metrics`, which holds the Prometheus auto-configuration.
- `application.yaml`: `logging.structured.format.console: ecs`; `prometheus` added to the exposure list. `service.name` comes from `spring.application.name`.
- `SecurityConfig`: added `PrometheusScrapeEndpoint` (Boot 4 package `org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus`) to the anonymous `EndpointRequest` matcher. Anything else on the management port still falls through to `anyExchange().authenticated()`.
- `ApplicationIT`: anonymous `/actuator/prometheus` returns 200 `text/plain` containing `jvm_memory_used_bytes`; anonymous `/actuator/env` returns 401.
- README: added the endpoint row and a Logging section. The plain-text override (`--logging.structured.format.console=`) was checked against the packaged jar.
- Unit tests and the build's app run now log ECS JSON too; human-readable output for developer runs is the deferred `local` profile item.
- `./mvnw -B clean verify` with 8080/8081 held: BUILD SUCCESS, 18 unit + 11 IT, coverage met, no OpenAPI drift, Spotless clean.
- Review patches: `management.metrics.tags.application` added; the Prometheus IT makes its own greeting request, then asserts `http_server_requests_seconds_count` with `application="untitled"` and `uri="/api/v1/greetings"`; the env test also asserts 404 with credentials. Re-verified: BUILD SUCCESS.

## Review Triage Log

| # | Layer | Finding | Verdict | Route | Evidence |
|---|-------|---------|---------|-------|----------|
| 1 | blind | `/actuator/env` test only hits the `anyExchange` fallback | low | patch | Renamed; now also asserts 404 with credentials, which pins the exposure boundary. |
| 2 | blind | Public metrics leak route tags; README silent on network restriction | low | patch | Anonymous access is the approved intent; README now says 8081 must stay internal. |
| 3 | blind | No `application` common tag | low | patch | One property; the IT asserts the tag. |
| 4 | blind | Prometheus IT only checks JVM metrics | low | patch | IT drives a greeting request and asserts the `http_server_requests` series. |
| 5 | blind | No automated test for ECS output | low | reject | Verified in build output (JSON lines with `service.name`); a test needs a new output-capture class. |
| 6 | blind | README override only for `spring-boot:run` | low | patch | Added jar and env-var forms; the jar form was run. |
| 7 | blind | `service.version`/`environment` undocumented | low | reject | Optional ECS fields, outside the intent. |
| 8 | blind | README does not say other management paths need auth | low | patch | Merged with row 2. |
| 9 | blind | No unit-level `SecurityConfig` test | low | reject | The IT covers it in every `verify`; only an explicit `-DskipITs` skips it. |
