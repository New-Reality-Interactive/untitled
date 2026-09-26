# untitled

Reactive Spring Boot 4.1 service (Java 25, Spring WebFlux) with Actuator health probes, a
generated OpenAPI 3.1 spec, ECS JSON logs, Prometheus metrics, HTTP Basic security and enforced
test coverage.

## Requirements

- JDK 25
- Nothing else: the Maven Wrapper (`./mvnw`) downloads Maven 3.9.16.

## Build

```sh
./mvnw clean verify
```

`verify` runs, in order:

1. Enforcer: JDK 25, Maven >= 3.9, no SNAPSHOT dependencies or plugins, upper-bound dependency versions.
2. Unit tests (Surefire), with JaCoCo collecting coverage.
3. Packages the app, then starts it (`spring-boot:start`, profile `it`) on free ports reserved for the
   build, so nothing needs 8080/8081 to be free.
4. Integration tests (`*IT`, Failsafe) against the running app, and generation of
   `target/openapi/openapi.json` from `/v3/api-docs`.
5. Stops the app, merges unit + integration coverage, writes the report to `target/site/jacoco/`
   and fails below 80% line or branch coverage.
6. Fails if `target/openapi/openapi.json` differs from the committed `docs/openapi.json`.
7. Spotless: fails if any file is not formatted (see [Formatting](#formatting)). This step also
   runs under `-DskipTests`/`-DskipITs`.

## Run

The API user's credentials have no default; startup fails without them.

```sh
export APP_SECURITY_USERNAME=me
export APP_SECURITY_PASSWORD=change-me
./mvnw spring-boot:run
```

They can also be set as `app.security.username` / `app.security.password` in any Spring Boot
configuration source. The build's throwaway credentials live in `src/test/resources/application-it.yaml`
and are not packaged into the jar.

`./mvnw verify -DskipTests` (or `-DskipITs`) also skips starting the app, generating the spec and the
drift and coverage checks.

## Endpoints

| Endpoint | Port | Access |
|----------|------|--------|
| `GET /api/v1/greetings?name=Ada` | 8080 | HTTP Basic |
| `GET /v3/api-docs` | 8080 | public |
| `GET /swagger-ui.html` | 8080 | public |
| `GET /actuator/health/liveness` | 8081 | public |
| `GET /actuator/health/readiness` | 8081 | public; lists components, no details |
| `GET /actuator/info` | 8081 | public |
| `GET /actuator/prometheus` | 8081 | public; Prometheus scrape format |

Every other path on 8081 needs HTTP Basic credentials, and only the endpoints above are exposed.
Health, info and Prometheus metrics are public on 8081 so probes and scrapers need no credentials.
The metrics include request URIs and error counts, so keep 8081 reachable only from inside the
cluster or network, never through a public ingress or load balancer. Metrics carry an
`application` tag set from `spring.application.name`.

```sh
curl -u "$APP_SECURITY_USERNAME:$APP_SECURITY_PASSWORD" 'http://localhost:8080/api/v1/greetings?name=Ada'
```

Invalid input (`name` missing, blank or longer than 100 characters) returns `400` with an
`application/problem+json` body.

## Logging

Console logs are structured JSON in the [Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/index.html)
(ECS) format, one object per line, with `service.name` set from `spring.application.name`. To get
plain-text logs for a single run, set the format to empty:

```sh
./mvnw spring-boot:run -Dspring-boot.run.arguments=--logging.structured.format.console=
java -jar target/untitled-*.jar --logging.structured.format.console=
LOGGING_STRUCTURED_FORMAT_CONSOLE= java -jar target/untitled-*.jar
```

## Formatting

Java is formatted with [google-java-format](https://github.com/google/google-java-format) (Google
style: 2-space indent, 100 columns), with unused imports removed and annotations formatted (type
annotations kept on the same line as the type). `pom.xml`, `src/**/*.yaml`, `src/**/*.yml`, the
root `*.md` files, `.editorconfig`, `.gitattributes`, `.gitignore` and `.mvn/**/*.properties` get
trailing whitespace trimmed, a final newline and spaces instead of tabs. `docs/openapi.json` is
not checked: it is generated and compared byte for byte. If `verify` fails at `spotless:check`,
fix the files with:

```sh
./mvnw spotless:apply
```

`.editorconfig` configures most editors to match. To skip the check for a single local build, pass
`-Dspotless.check.skip=true`.

## Updating the OpenAPI spec

`docs/openapi.json` is the committed contract. After changing the API (or the project version,
which appears in `info.version`), run the build; it fails at the drift check with the new spec
already generated. Review and accept it with:

```sh
./mvnw verify            # fails: docs/openapi.json is out of date
cp target/openapi/openapi.json docs/openapi.json
./mvnw verify            # passes
```

## Dependency and plugin versions

Libraries use the versions managed by Spring Boot (springdoc is the one unmanaged library). Every
build plugin runs its latest release: where Boot manages an older version, or does not manage the
plugin at all, the version is pinned in the `<properties>` of `pom.xml`; the rest already get the
latest release from Boot. To check for updates (milestones and release candidates are ignored):

```sh
./mvnw versions:display-plugin-updates versions:display-dependency-updates
```

The google-java-format version (`google-java-format.version` in `pom.xml`) is Spotless
configuration, not a plugin, so `versions:display-plugin-updates` does not report it; check
[its releases](https://github.com/google/google-java-format/releases) when updating Spotless.
