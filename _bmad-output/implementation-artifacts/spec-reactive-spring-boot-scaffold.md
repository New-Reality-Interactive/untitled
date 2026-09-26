---
title: 'Java 25 reactive Spring Boot service scaffold'
type: 'feature'
created: '2026-09-25'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'NO_VCS'
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The project directory is empty; there is no service to build features on.

**Approach:** Scaffold a Maven, Java 25, Spring WebFlux service with Actuator health probes, springdoc OpenAPI generated during `mvn verify`, JaCoCo coverage enforced at >= 80%, and dependencies proven compatible by a green `mvn verify`.

## Boundaries & Constraints

**Decisions:** Coordinates `com.newrealityinteractive:untitled`, package `com.newrealityinteractive.untitled`. Spring Boot 4.1.1 GA; no milestones/RCs anywhere. Version policy: libraries use Boot-managed versions; every Maven build plugin is pinned to its latest release even when Boot manages an older one. Spring Security included: health/info, API docs, Swagger UI public; everything else HTTP Basic; credentials from config/env, never in source; startup fails if unset (IT build supplies them via an `it` profile). Generated OpenAPI spec committed at `docs/openapi.json`; build fails when it drifts. Deferred to follow-ups (see `deferred-work.md`): structured logging, Prometheus, SBOM, Spotless/.editorconfig, `local` profile.

**Always:** Java 25; enforcer requires JDK 25, Maven >= 3.9, bans SNAPSHOTs, `requireUpperBoundDeps`; coverage counts unit + integration tests, 80% line and branch; Maven Wrapper committed; write Boot 4 code (modular starters, moved packages, Jackson 3), not Boot 3 idioms.

**Never:** Blocking I/O on reactor threads; databases, messaging, tracing, CORS, container images, Docker/K8s manifests, CI files; the deferred items above; excluding production classes from coverage (only `main` bootstrap class); fixed ports in the build.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Liveness | anonymous `GET /actuator/health/liveness` (mgmt port) | 200 UP | N/A |
| Readiness | anonymous `GET /actuator/health/readiness` | 200 UP; components list custom indicator | indicator DOWN → 503 |
| API docs | anonymous `GET /v3/api-docs` | OpenAPI 3.1 JSON incl. sample endpoint + basic auth scheme | N/A |
| Swagger UI | anonymous `GET /swagger-ui.html` | UI loads (webjars permitted) | N/A |
| Greeting, valid | authenticated `GET /api/v1/greetings?name=Ada` | 200 JSON greeting | N/A |
| Greeting, anonymous | no credentials | 401 | N/A |
| Greeting, invalid | `name` blank or > 100 chars | 400 `application/problem+json` | ProblemDetail |

</frozen-after-approval>

## Code Map

- Project root empty apart from `_bmad*` tooling; do not touch `_bmad/`, `_bmad-output/`. Not a git repo.
- Latest plugin releases (Central, 2026-09-25): springdoc-openapi-maven-plugin 1.5, jacoco 0.8.15, surefire/failsafe 3.6.0, enforcer 3.6.3, build-helper 3.6.2, git-commit-id 10.0.1, versions 2.22.0. Recheck all other Boot-managed plugins (compiler, resources, jar, install, clean, wrapper…) with `versions:display-plugin-updates` and pin newer ones.
- Libraries: springdoc-openapi-starter-webflux-ui 3.1.1 (not Boot-managed; built on Boot 4.1.0, pulls swagger-core on Jackson 2 — confirm it coexists with Boot's Jackson 3); everything else Boot-managed (Mockito 5.23.0).
- Toolchain: JDK 25.0.4.1, Maven 3.9.16.

## Tasks & Acceptance

**Execution:**
- [x] `pom.xml` -- deps: webflux, actuator, validation, security, springdoc; test: starter-test, starter-webflux-test, starter-security-test, reactor-test. Build: enforcer; build-helper `reserve-network-port` (app + mgmt); JaCoCo `prepare-agent` (unit) and `prepare-agent-integration` into a property passed to `spring-boot:start` `jvmArguments`, with reserved ports and `it` profile; failsafe receives ports via system properties; springdoc plugin `apiDocsUrl` on reserved port → `target/openapi/openapi.json`; post-integration order `stop` → JaCoCo `merge` → `report` → `check`; drift check generated vs `docs/openapi.json`; Mockito `-javaagent` in surefire/failsafe `argLine` with `@{argLine}`; build-info; git-commit-id `failOnNoGitDirectory=false`; versions rules ignoring `-M`/`-RC`.
- [x] `.mvn/wrapper/*`, `mvnw`, `mvnw.cmd` -- via `mvn wrapper:wrapper`.
- [x] `src/main/java/.../Application.java` -- bootstrap.
- [x] `.../greeting/*` -- validated WebFlux endpoint, record DTO, OpenAPI annotations.
- [x] `.../config/OpenApiConfig.java` -- metadata, basic security scheme, version via `ObjectProvider<BuildProperties>` (absent-safe).
- [x] `.../config/SecurityConfig.java` -- `SecurityWebFilterChain`: `EndpointRequest` health/info, api-docs, swagger-ui, `/webjars/**` permitted; rest Basic; stateless; CSRF off; default headers.
- [x] `.../health/*` -- `ReactiveHealthIndicator` (Boot 4 package) in readiness group.
- [x] `.../error/*` -- validation → ProblemDetail.
- [x] `src/main/resources/application.yaml`, `application-it.yaml` -- mgmt port 8081 default; expose health, info; probes; readiness `show-components: always`, details never; graceful shutdown; `springdoc.api-docs.version=openapi_3_1`.
- [x] `src/test/java/...` -- unit tests + `*IT` against the started app; cover every branch.
- [x] `docs/openapi.json` -- committed spec.
- [x] `.gitignore`, `README.md` -- build/run/endpoints/credentials/updating the spec.

**Acceptance Criteria:**
- Given JDK 25 and busy ports 8080/8081, when `./mvnw verify` runs, then it succeeds with coverage >= 80% line and branch including IT execution.
- Given coverage below 80% or `docs/openapi.json` differing from the generated spec, when `./mvnw verify` runs, then the build fails.
- Given the POM, when versions display goals run, then no non-pre-release plugin updates are reported.

## Implementation Notes

- Jackson 2 upper-bound exemption: springdoc 3.1.1 → swagger-core 2.2.55 asks for Jackson 2.22.x; Boot 4.1.1 manages 2.21.5. Per version policy Boot's version wins, so the four conflicting `com.fasterxml.jackson.*` artifacts are listed in `requireUpperBoundDeps` excludes (the rule does not accept wildcards). ITs exercise swagger-core on 2.21.5.
- Plugin ordering: JaCoCo is declared after `spring-boot-maven-plugin` so `stop` precedes `merge`/`report`/`check` in post-integration-test; `prepare-agent-integration` is bound to `package` so its property exists before `start`.
- JMX port for `spring-boot:start`/`stop` is reserved too (default 9001 would be a fixed port).
- OpenAPI `servers` fixed to `/` so the generated spec does not embed the random build port; drift check is `maven-antrun-plugin` `filesmatch` (text mode) in `verify`.
- `maven-site-plugin` pinned (3.22.0) because `requirePluginVersions` flags it via default lifecycle bindings.
- `--enable-native-access=ALL-UNNAMED` passed to forked JVMs to silence Netty's JDK 25 restricted-method warnings.
- `application-it.yaml` lives in `src/test/resources` (not `src/main/resources` as the task list says) so fixed IT credentials never ship in the jar; `spring-boot:start` adds `target/test-classes/` via `spring.config.additional-location`. The `it` profile mechanism is unchanged.
- 400 responses are documented with a `ValidationProblem` schema (flat `errors` extension) instead of springdoc's `ProblemDetail` model, which shows a nested `properties` member that is never serialized.
- `-DskipTests`/`-DskipITs` skip app start/stop, spec generation, JaCoCo merge/report/check and the drift check.
- Readiness aggregate is `DOWN` (not `OUT_OF_SERVICE`) when the custom component is DOWN; HTTP 503 as specified.

## Spec Change Log

## Review Triage Log

| # | Layer | Finding | Verdict | Route | Evidence |
|---|-------|---------|---------|-------|----------|
| 1 | verification-gap | Blank username with valid password untested | medium | patch | Pre-verified; added `failsStartupWhenUsernameIsBlank`. |
| 2 | verification-gap | Stateless (no session) behaviour untested | medium | patch | Pre-verified; unit + IT now assert no `Set-Cookie` on authenticated 200. |
| 3 | blind | OpenAPI 400 schema shows nested `properties`, omits `errors` | medium | patch | Jackson serializes ProblemDetail properties flat; committed contract was wrong for client generation. Documented via `ValidationProblem`. |
| 4 | blind | Missing-param 400 lacks `errors`/different detail | low | reject | Both are valid RFC 9457 bodies; `errors` now documented as optional; normalizing adds handler overrides. |
| 5 | blind | 401 not problem+json / undocumented header | false | reject | README claims problem+json only for invalid input; spec matrix requires only 401. |
| 6 | blind | `@Size(min=1)` duplicates `@NotBlank` (2 errors for `name=`) | low | reject | Both messages accurate; removing `min` regresses documented `minLength: 1`. |
| 7 | blind | `application-it.yaml` with fixed creds shipped in prod jar | medium | patch | Confirmed in jar; moved to test resources, loaded by additional-location; jar now holds only `application.yaml`. |
| 8 | blind | `@{argLine}` breaks with `-Djacoco.skip` | false | reject | Ran `-Djacoco.skip=true test`: JaCoCo logs "argLine set to empty", 17 tests pass. |
| 9 | blind | Wrapper lacks `distributionSha256Sum` | low | patch | One-line fix; checksum derived from zip verified against Central's .sha512; fresh-download run passed. |
| 10 | blind | No `.gitattributes` for mvnw line endings | low | patch | Added LF/CRLF rules. |
| 11 | blind | `info.version` from build-info forces spec regen on version bump | false | reject | Spec task mandates version via `BuildProperties`; README documents regeneration. |
| 12 | blind | Drift check fails on editor-added final newline | false | reject | Tested: one appended newline passes `filesmatch textfile`; only content changes fail. |
| 13 | blind | `/actuator/info` public | false | reject | Intent: health/info public. |
| 14 | blind | Swagger/api-docs public in all envs | false | reject | Intent: API docs and Swagger UI public. |
| 15 | blind | bcrypt verification on every stateless Basic request | low | reject | Runs on boundedElastic (off reactor threads); standard Spring Security Basic behaviour; changing encoder is a security trade-off not needed for scaffold load. |
| 16 | blind | Readiness `greeting` indicator gives no real signal | false | reject | Intent requires a custom readiness indicator; it is DOWN-able and tested. |
| 17 | blind, edge | `-DskipTests`/`-DskipITs` still start app and fail coverage | low | patch | Verified `install -DskipTests` path would fail; added `skip` wiring; `verify -DskipTests` now BUILD SUCCESS without starting app. |
| 18 | blind | README overstates plugin pinning | low | patch | Reworded; versions check confirms every plugin is latest. |
| 19 | blind | `Greeting.message` not required in schema | low | patch | Added `requiredMode = REQUIRED`. |
| 20 | edge | Synchronous throw from `greet` escapes `onErrorResume` | false | reject | `GreetingService.greet` uses `Mono.fromSupplier`; cannot throw at assembly. |
| 21 | edge | `BuildProperties` without version → null `info.version` | false | reject | `build-info` always writes `build.version`. |
| 22 | edge | Username containing `:` can never authenticate | low | reject | Misconfiguration only; guard adds validation surface. |
| 23 | edge | Cross-parameter / null names in `errors` | false | reject | No cross-parameter constraints; `-parameters` enabled by Boot parent; Hibernate messages non-null. |
| 24 | edge | `greet(null)` returns "Hello, null!" | false | reject | Only caller is the validated controller (`@NotBlank`). |
| 25 | edge | Mockito javaagent path unquoted (spaces in repo path) | low | patch | Direct fix: quoted the `-javaagent` argument. |
| 26 | edge | Port reserved long before app start (race) | low | patch | Moved `reserve-network-port` to `pre-integration-test`, just before `start`. |
| 27 | edge | App left running if an integration-test-phase goal aborts the build | low | reject | Only springdoc generate can abort there (failsafe defers failures to verify); rare, fix needs failure hooks. |
| 28 | edge | Missing generated spec yields misleading drift message | low | reject | Generation failure aborts the build first; skip paths now also skip the drift check. |
| 29 | edge | Null `Location` in Swagger IT gives NPE not assertion | low | reject | Test-only diagnostics; still fails the build. |

## Verification

**Commands:**
- `./mvnw -B clean verify` -- BUILD SUCCESS; `target/openapi/openapi.json` equals `docs/openapi.json`; coverage check passes
- `./mvnw -B versions:display-plugin-updates versions:display-dependency-updates` -- no plugin updates; library updates only where Boot pins older
