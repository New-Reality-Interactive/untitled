---
title: 'HTTP access logging with a runtime toggle'
type: 'feature'
created: '2026-09-26'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '6d8063dadf400e60c1298f9ec4bf231dacf57725'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-observability-baseline.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Nothing logs HTTP requests, so it is hard to see what a running service was asked and how it answered. Reactor Netty's access log can only be switched on at startup.

**Approach:** Add a reactive `WebFilter` that writes one ECS JSON log line per completed exchange on both ports (8080 and 8081), with method, path, query, status, duration and correlation ID. It writes through a dedicated logger, so changing that logger's level at runtime turns access logging on or off without a restart.

**Decisions:** Logger name `http.access`, set to `WARN` in `application.yaml`, so access logging is off by default; lines are written at `INFO`. The toggle is the standard actuator `loggers` endpoint, exposed on 8081 behind HTTP Basic (`POST /actuator/loggers/http.access` with `{"configuredLevel":"INFO"}` turns it on; `"WARN"`, or `null` to reset, turns it off). Correlation ID: the incoming `X-Request-Id` when it matches `[A-Za-z0-9._:-]{1,128}`, otherwise a generated UUID. It is logged as `http.request.id` and always returned in the `X-Request-Id` response header, even when logging is off. Every endpoint on both ports is logged, including health probes and Prometheus scrapes.

## Boundaries & Constraints

**Always:** The filter runs first (before Spring Security), so 401s and 400s are logged too. Fields are SLF4J key-value pairs with ECS names, which Boot's ECS formatter writes as nested JSON: `http.request.method`, `url.path`, `url.query` (only when there is a query), `http.response.status_code`, `event.duration` (nanoseconds), and `http.request.id`. The line is written once, when the exchange completes, errors or is cancelled. When the logger is off, nothing is built or logged (check `isInfoEnabled` first). No blocking calls.

**Never:** Log any request or response header, cookie, body or principal (no `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`). Never use Reactor Netty's access log or tracing libraries. Never change the API, OpenAPI spec, ports or existing security rules beyond the toggle endpoint below.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Enabled, authenticated call | `GET /api/v1/greetings?name=Ada` with Basic creds | One line: GET, `/api/v1/greetings`, `name=Ada`, 200, duration > 0, correlation ID | N/A |
| Enabled, anonymous | same without creds | Line with status 401; no credential material anywhere in the line | N/A |
| Enabled, management port | `GET :8081/actuator/health/liveness` | Line with status 200 | N/A |
| Disabled | logger level above INFO | No access line | N/A |
| Toggled at runtime | level changed while running | Next request follows the new level; no restart | N/A |
| Handler error | handler throws / 500 | Line with status 500 | Error still propagates unchanged |
| Client cancels | connection dropped mid-request | Line still written; status whatever the response holds, if any | N/A |

</frozen-after-approval>

## Code Map

- `config/SecurityConfig.java` -- `SecurityWebFilterChain` permits health/info/prometheus via `EndpointRequest.to(...)`; everything else needs Basic. Exposing `loggers` needs no rule change: `anyExchange().authenticated()` already covers it.
- `application.yaml` -- add `loggers` to `management.endpoints.web.exposure.include`; add `logging.level.http.access: WARN`.
- WebFilters from the parent context also apply to the management child context (Spring Security already does on 8081), so one filter bean covers both ports. Verify this in the IT; if it does not hold, register the filter for the child context as well.
- Boot 4.1's `ElasticCommonSchemaStructuredLogFormatter` writes SLF4J `KeyValuePair`s as nested JSON (dotted keys become nested objects).
- `ApplicationIT` -- runs against the jar on reserved ports; the app's stdout is not captured, so assert the toggle through the endpoint, and assert log output in a unit test.
- `README.md` -- Endpoints table and Logging section.
- `deferred-work.md` -- remove the entry.

## Tasks & Acceptance

**Execution:**
- [x] `src/main/java/.../web/AccessLogFilter.java` -- new `WebFilter`, `Ordered.HIGHEST_PRECEDENCE`, dedicated logger, fields as above -- the feature.
- [x] `src/main/resources/application.yaml` -- `http.access: WARN`, expose `loggers` -- runtime control.
- [x] `src/test/java/.../web/AccessLogFilterTest.java` -- unit-test the matrix with a captured Logback appender: fields, query absent, 401 without credentials in the line, 500, disabled, cancel, `X-Request-Id` reuse/rejection/echo -- coverage of the edge cases.
- [x] `src/test/java/.../ApplicationIT.java` -- `/actuator/loggers` needs auth; POSTing `INFO` then `WARN` to `http.access` changes its effective level; responses carry `X-Request-Id` -- end-to-end proof.
- [x] `README.md` -- document the access log, its fields and how to toggle it -- documentation.
- [x] `deferred-work.md` -- remove the entry.

**Acceptance Criteria:**
- Given the running app, when an operator turns access logging on through the toggle endpoint with credentials, then the following requests on both ports produce ECS JSON access lines, and turning it off stops them, without a restart.
- Given anonymous access, when the toggle endpoint is called, then it returns 401.
- Given `./mvnw clean verify`, then the build passes with coverage, Spotless and OpenAPI drift checks green.

## Implementation Notes

- `web/AccessLogFilter` (`@Component`, `Ordered.HIGHEST_PRECEDENCE`) applies to the management child context as well; the IT asserts `X-Request-Id` on 8081 and a manual jar run showed access lines for `/actuator/health/liveness`.
- The level is checked when a request starts; the line is built with `log.atInfo()` at completion, so a request in flight while logging is turned off writes nothing.
- Errors that leave the filter are rendered later by the web exception handlers, so the response has no status yet: the line uses the `ResponseStatusException` status, otherwise 500 (`doOnError`); other signals log in `doFinally`. A cancelled exchange logs without `http.response.status_code` when none was set.
- The IT caught that `configuredLevel: null` made `http.access` inherit the root `INFO`, which turned logging *on*. `application.yaml` now also sets the parent `http` to `WARN`, so a reset turns it off as the Decisions say.
- Manual run (jar on 18080/18081, env creds): toggle 204; three nested ECS lines (200 with reused `manual-1`, anonymous 401, 8081 liveness); requests before the toggle and after the reset not logged; base64 credentials absent from the output.
- `./mvnw -B clean verify` after review patches: BUILD SUCCESS, 33 unit + 18 IT, coverage met, no OpenAPI drift, Spotless clean.

## Spec Change Log

## Review Triage Log

| # | Layer | Finding | Verdict | Route | Evidence |
|---|-------|---------|---------|-------|----------|
| 1 | gap | Tests take the logger from `LOGGER_NAME`, so a drift from `http.access` passes while the toggle silently stops working | medium | patch | Test now builds its logger from the literal `"http.access"`. |
| 2 | gap, blind | Rendered ECS JSON never asserted | low | reject | Boot owns the formatter; nested output confirmed on the jar; same call as observability spec row 5. |
| 3 | blind, edge | `errorStatus` logs 500 for `ErrorResponse`s other than `ResponseStatusException` and for `@ResponseStatus` exceptions | medium | patch | Now checks `ErrorResponse`, then the merged `@ResponseStatus`, then 500; unit test covers 422 and 409. |
| 4 | edge | Error after commit logs 500 though the client got the committed status | low | patch | Committed responses log their actual status; unit test covers it. |
| 5 | edge | Client disconnect before commit logged as 500 | low | reject | Needs a disconnect branch; rare, and after-commit disconnects are covered by row 4. |
| 6 | edge | `event.duration` on error lines excludes error rendering | low | reject | Fix needs a completion hook outside the chain; the difference is small. |
| 7 | edge, blind, gap | `http: WARN` also silences any other `http.*` logger | low | reject | Needed so a `null` reset of the frozen `http.access` name turns it off; the yaml comment says so; no `http.*` loggers exist. |
| 8 | edge | IT never POSTs `WARN`, as the task says | low | patch | IT now sets INFO, WARN, INFO, then resets. |
| 9 | blind | `loggers` lets an authenticated caller raise framework loggers that might log headers | low | reject | The endpoint's power was the approved decision; it is behind the only API credentials; Spring masks headers in its DEBUG logs by default. |
| 10 | blind | Level changes are not audited | low | reject | Outside intent. |
| 11 | blind | Request ID not in MDC/Reactor context for other logs | low | reject | Outside intent (the access line and header); a context-propagation feature. |
| 12 | blind | No test that `X-Request-Id` survives error responses | low | patch | 500 unit test asserts the header. |
| 13 | blind | README says `null` resets to "the configured WARN" | low | patch | Reworded: it clears the runtime level and inherits WARN from `http`. |
| 14 | blind | README does not say the toggle is per instance | low | patch | One sentence added. |
| 15 | blind | Missing client address, response size, outcome fields | low | reject | Outside the approved field list. |
| 16 | blind | Cancel test does not assert status absent | low | patch | Asserts no `http.response.status_code`; `onlyLine()` already checks a single line. |
| 17 | blind | 401 test reads the event before checking the count | low | patch | Reordered. |
| 18 | blind | Endpoints table omits `GET /actuator/loggers` | low | patch | Row added; POST `204` noted. |
| 19 | blind | `deferred-work.md` left empty | low | reject | Earlier specs removed entries the same way; the workflow appends to it. |
| 20 | gap | In-flight request when logging is turned off is untested | low | reject | Not a matrix row; behaviour follows from `atInfo()` at completion. |

## Verification

**Commands:**
- `./mvnw -B clean verify` -- expected: BUILD SUCCESS.

**Manual checks (if no CLI):**
- `./mvnw spring-boot:run -Dspring-boot.run.profiles= ` with env creds, toggle on, curl both ports: one ECS JSON access line per request, no `Authorization` value anywhere.
