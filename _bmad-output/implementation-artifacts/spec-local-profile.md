---
title: 'local profile for developer runs'
type: 'feature'
created: '2026-09-26'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '83ac52c8c0c10798ed1d9a41075015b07255bcf7'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-reactive-spring-boot-scaffold.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Running the service on a developer machine means exporting `APP_SECURITY_USERNAME`/`APP_SECURITY_PASSWORD` by hand and reading ECS JSON logs, which are built for log shippers, not people.

**Approach:** Add a `local` Spring profile that supplies throwaway developer credentials and switches console logging back to Spring Boot's plain-text format, and document how to use it in the README.

**Decisions:** Credentials live in a committed `config/application-local.yaml` at the project root with fixed throwaway values `local`/`local` (read from `./config/` in the working directory, never packaged). `./mvnw spring-boot:run` activates `local` by default through the `spring-boot.run.profiles` property in `pom.xml`; `-Dspring-boot.run.profiles=` turns it off, and `java -jar` needs `--spring.profiles.active=local`.

## Boundaries & Constraints

**Always:** Local credentials and profile config never end up in the packaged jar (same rule as the `it` profile). The default (no profile) behaviour is unchanged: ECS JSON logs, and startup fails without credentials. The `it` build run keeps its own profile and credentials.

**Never:** Defaults for `app.security.*` in `application.yaml`; putting the local file under `src/main/resources`; changing ports, security rules or the OpenAPI spec; IDE launch configs (`.vscode/` is gitignored).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Local run | `local` profile active, no env vars | App starts, plain-text console logs, local credentials accepted on `/api/v1/greetings` | N/A |
| Env override | `local` active and `APP_SECURITY_*` set | Env credentials win over the file's | N/A |
| Packaged jar, no profile | `java -jar`, no credentials | Startup fails as today; jar contains no `application-local.yaml` | Existing validation failure |
| Build | `./mvnw clean verify` | `it` profile only; ECS logs; all checks pass | N/A |

</frozen-after-approval>

## Code Map

- `src/main/resources/application.yaml` -- sets `logging.structured.format.console: ecs`; comment says credentials have no default. Do not add defaults here.
- `src/test/resources/application-it.yaml` -- precedent: throwaway credentials kept out of the jar.
- `pom.xml` `spring-boot-maven-plugin` (~L242) -- `start-app` execution sets `<profiles>it</profiles>` explicitly, so a `spring-boot.run.profiles` property does not reach it. Spotless `<misc>` includes (~L454) cover `src/**/*.yaml` only; add `config/**/*.yaml`.
- Matrix rows "Local run" and "Env override" are automated in `config/LocalProfileTest` (Surefire, project root as working dir): the app starts with profile `local` on port 0 using a `StandardReactiveWebEnvironment` whose `systemEnvironment` source is replaced by one holding only the test's variables (none, or `APP_SECURITY_*` for the override case), so the file-vs-env precedence is real and a developer's own env vars cannot skew it. A third test asserts `/application-local.yaml` is not on the classpath. It asserts local/local binding, empty `logging.structured.format.console`, 200 with local:local and 401 anonymous, and that env credentials win.
- `config/ApiUserProperties.java` -- `@NotBlank` username/password; unchanged.
- `README.md` -- Run and Logging sections describe env vars and the empty-format override; update both.
- `_bmad-output/implementation-artifacts/deferred-work.md` -- remove the `local` profile entry.

## Tasks & Acceptance

**Execution:**
- [x] `config/application-local.yaml` -- new: `logging.structured.format.console:` empty (plain text) plus `app.security.username/password: local` -- the profile itself.
- [x] `pom.xml` -- add `<spring-boot.run.profiles>local</spring-boot.run.profiles>`; add `config/**/*.yaml` to Spotless includes -- default activation and formatting coverage.
- [x] `README.md` -- Run section leads with the local profile; Logging section points to it for plain text; note the file is not packaged -- documentation.
- [x] `deferred-work.md` -- remove the entry.

**Acceptance Criteria:**
- Given a fresh clone, when a developer runs the documented local command, then the app starts with plain-text logs and `curl -u <local creds>` on the greeting endpoint returns 200.
- Given `./mvnw clean verify`, then the build passes and `unzip -l target/untitled-*.jar` lists no `application-local.yaml`.

## Implementation Notes

- The `start-app` execution declared `<profiles><profile>it</profile></profiles>`, not `<profiles>it</profiles>` as the Code Map says. In child-element form Maven also evaluates the parameter's `${spring-boot.run.profiles}` expression, so the IT app started with `local` (8 ITs failed with 401). Switched to the scalar `<profiles>it</profiles>`; the IT app now runs with `it` only and ECS logs.
- `console: ""` (explicit empty string) rather than a bare `console:`, so the override is unambiguous.
- README: the `./mvnw spring-boot:run -Dspring-boot.run.arguments=--logging.structured.format.console=` example was dropped from Logging, since `spring-boot:run` is plain text by default now; Formatting lists `config/**/*.yaml`.

## Spec Change Log

## Review Triage Log

| # | Layer | Finding | Verdict | Route | Evidence |
|---|-------|---------|---------|-------|----------|
| 1 | edge, blind, gap | `LocalProfileTest` copies the real env minus uppercase `APP_SECURITY_*`, so `LOGGING_*`/`SPRING_*`/lowercase credential env vars can flip its assertions | low | patch | `start()` passes the rest of `System.getenv()` through; starting from an empty map is simpler and deterministic. |
| 2 | edge | JVM system properties (`-Dapp.security.*`) could skew the test | low | reject | Surefire forks with no such properties configured; guarding it adds a second replaced source for an unlikely setup. |
| 3 | blind | No automated check that `application-local.yaml` stays out of the jar | low | patch | Matrix row with manual-only coverage; asserting the resource is absent from the test classpath (built from `target/classes`, which the jar packages) is one assertion. |
| 4 | blind | `-Dspring-boot.run.profiles=` off-switch never checked | false | reject | Verified by hand during implementation: no active profile, startup failed on missing credentials. |
| 5 | blind | Global `spring-boot.run.profiles` property also reaches other `start`/`run` executions | low | reject | Activation via this property is a frozen decision; the one existing `start` execution is pinned and commented. |
| 6 | blind | Code Map still states the wrong `<profiles>` premise | low | reject | Fix edits this build's spec; Implementation Notes already record the correction. |
| 7 | blind, edge | Spotless covers `config/**/*.yaml` but not `*.yml` | low | patch | `src/` lists both; one include plus README mention. |
| 8 | blind | Env-override test only checks binding, not an HTTP 200/401 | low | reject | `SecurityConfig` builds the user from the `ApiUserProperties` bean the test asserts on; the first test already proves the HTTP path. |
| 9 | blind | README Run block puts `curl` right after blocking `spring-boot:run` | low | patch | Pasting the block never reaches `curl`; say "in another terminal". |
| 10 | blind | README's no-profile example uses `java -jar` without saying the jar must be built first | low | patch | `target/untitled-*.jar` only exists after `./mvnw package`/`verify`. |
| 11 | blind | Running from a subdirectory gives a misleading missing-credentials error | low | reject | `spring-boot:run` forks in `${basedir}`; README already says to run from the project root. |
| 12 | gap | Nothing automatically checks that `spring-boot:run` activates `local` | low | reject | Needs a Maven-invoker harness; a break is immediately visible on the first local run. |
| 13 | gap | IT app running `local,it` would pass ITs with plain-text logs | low | reject | Requires reverting the commented scalar form; guarding it needs a new log-format assertion. |

## Verification

**Commands:**
- `./mvnw -B clean verify` -- expected: BUILD SUCCESS, Spotless clean, no OpenAPI drift.
- `unzip -l target/untitled-*.jar | grep -c application-local` -- expected: 0.
- Run the documented local command, then `curl -u local:local 'http://localhost:8080/api/v1/greetings?name=Ada'` -- expected: 200 and plain-text log lines.
