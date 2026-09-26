# untitled

Reactive Spring Boot 4.1 service (Java 25, Spring WebFlux) with Actuator health probes, a
generated OpenAPI 3.1 spec, ECS JSON logs, a runtime-toggleable HTTP access log, Prometheus metrics, a CycloneDX SBOM, HTTP Basic security
and enforced test coverage.

## Requirements

- JDK 25
- Nothing else: the Maven Wrapper (`./mvnw`) downloads Maven 3.9.16.

## Build

```sh
./mvnw clean verify
```

`verify` runs, in order:

1. Enforcer: JDK 25, Maven >= 3.9, no SNAPSHOT dependencies or plugins, upper-bound dependency versions.
2. Writes a CycloneDX SBOM of the dependencies (see [SBOM](#sbom)) into `target/classes`.
3. Unit tests (Surefire), with JaCoCo collecting coverage.
4. Packages the app, then starts it (`spring-boot:start`, profile `it`) on free ports reserved for the
   build, so nothing needs 8080/8081 to be free.
5. Integration tests (`*IT`, Failsafe) against the running app, and generation of
   `target/openapi/openapi.json` from `/v3/api-docs`.
6. Stops the app, merges unit + integration coverage, writes the report to `target/site/jacoco/`
   and fails below 80% line or branch coverage.
7. Fails if `target/openapi/openapi.json` differs from the committed `docs/openapi.json`.
8. Spotless: fails if any file is not formatted (see [Formatting](#formatting)). This step also
   runs under `-DskipTests`/`-DskipITs`.

## Run

On a developer machine, start the app:

```sh
./mvnw spring-boot:run
```

It keeps running in the foreground. Once it is up, call it from a second terminal:

```sh
curl -u local:local 'http://localhost:8080/api/v1/greetings?name=Ada'
```

`spring-boot:run` activates the `local` profile by default (the `spring-boot.run.profiles` property
in `pom.xml`). It reads `config/application-local.yaml`, which sets throwaway credentials
`local`/`local` and plain-text console logs. Spring Boot picks the file up from `./config/` in the
working directory, so it is never packaged into the jar; run from the project root. To use it with
the jar (build it first with `./mvnw package` or `./mvnw verify`):

```sh
java -jar target/untitled-*.jar --spring.profiles.active=local
```

`APP_SECURITY_USERNAME` / `APP_SECURITY_PASSWORD` still override the file's credentials.

Without the `local` profile (the default for `java -jar`, or `./mvnw spring-boot:run
-Dspring-boot.run.profiles=`), the API user's credentials have no default and startup fails
without them. The jar must be built first (`./mvnw package` or `./mvnw verify`):

```sh
export APP_SECURITY_USERNAME=me
export APP_SECURITY_PASSWORD=change-me
java -jar target/untitled-*.jar
```

They can also be set as `app.security.username` / `app.security.password` in any Spring Boot
configuration source. The build's throwaway credentials live in `src/test/resources/application-it.yaml`
and are not packaged into the jar; the build runs the app with the `it` profile only.

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
| `GET /actuator/loggers` | 8081 | HTTP Basic; lists loggers and their levels |
| `GET, POST /actuator/loggers/{name}` | 8081 | HTTP Basic; read a logger's level, or set it with a JSON body (`204`) |
| `GET /actuator/sbom` | 8081 | HTTP Basic; lists SBOM ids |
| `GET /actuator/sbom/application` | 8081 | HTTP Basic; CycloneDX JSON |

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

Every response on both ports carries an `X-Request-Id` header. A caller's own `X-Request-Id` is
reused when it matches `[A-Za-z0-9._:-]{1,128}`; otherwise the service generates a UUID. It is the
`http.request.id` of the request's access log line (see [Access log](#access-log)).

## SBOM

Every Maven build writes a [CycloneDX](https://cyclonedx.org/) JSON software bill of materials of
the dependencies to `target/classes/META-INF/sbom/application.cdx.json`, so it is packaged inside
the jar. Test-scope dependencies are left out, and the configuration processor runs from the
compiler plugin's `annotationProcessorPaths` rather than as a dependency, so the SBOM lists what the
jar ships. The Spring Boot parent configures `cyclonedx-maven-plugin`; the build only switches it
on. A running service serves its own SBOM on 8081, behind the API's HTTP Basic credentials
because it lists exact library versions:

```sh
curl -u "$APP_SECURITY_USERNAME:$APP_SECURITY_PASSWORD" http://localhost:8081/actuator/sbom/application
unzip -p target/untitled-*.jar META-INF/sbom/application.cdx.json
```

`GET /actuator/sbom` lists the available SBOM ids (`application`). The file comes from the Maven
build, so an app started from classes an IDE compiled on its own has no SBOM: the id list is empty
and `/actuator/sbom/application` returns 404.

## Logging

Console logs are structured JSON in the [Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/index.html)
(ECS) format, one object per line, with `service.name` set from `spring.application.name`. The
`local` profile (see [Run](#run)) switches to Spring Boot's plain-text format. To get plain-text
logs for a single run without it, set the format to empty:

```sh
java -jar target/untitled-*.jar --logging.structured.format.console=
LOGGING_STRUCTURED_FORMAT_CONSOLE= java -jar target/untitled-*.jar
```

### Access log

The service can log one line per HTTP request on both ports (8080 and 8081), including rejected
ones (401, 400) and health probes and Prometheus scrapes. A line holds:

- `http.request.method`, `url.path`, `url.query` (only when there is one),
  `http.response.status_code` and `event.duration` (nanoseconds).
- `http.request.id`: the `X-Request-Id` response header, which is the caller's value when it sent a
  well-formed one and a generated UUID otherwise.
- `http.request.header` and `http.response.header`: every header as
  `{"name": ["value", ...]}`, with names lowercased. The values of `Authorization`,
  `Proxy-Authorization`, `Cookie` and `Set-Cookie` are replaced by `[REDACTED]`.
- `http.request.body.content` / `http.response.body.content` and `.bytes`, for JSON bodies only
  (`application/json` and `+json` types such as problem details). A body that parses is logged as
  raw, compacted JSON (with ECS, a nested object); one cut at 8 KiB or invalid is logged as a
  string. `.bytes` is the full size. Form data, Prometheus text, HTML and streams are not logged,
  and a request body appears only when the application reads it, so a request rejected with 401
  logs none.

With ECS the message is only a summary (`POST /actuator/loggers/x 204`). The plain-text console of
the `local` profile drops the separate fields, so there the message repeats all of them:

```text
GET /api/v1/greetings?name=Ada 200 80.379ms X-Request-Id=ada-test-001 request.header={"host":["localhost:8080"],"authorization":["[REDACTED]"]} response.header={"x-request-id":["ada-test-001"],"content-type":["application/json"]} response.body={"message":"Hello, Ada!"}
```

(`request.body=` follows `request.header=` when the request has one.) Credentials are redacted and
the user is not logged; query strings, other headers and JSON bodies are, so keep secrets out of
them.

It is written by the `http.access` logger at `INFO`, and that logger is at `WARN` by default, so the
access log is off. Turn it on and off while the service runs, without a restart, through the
`loggers` endpoint on 8081 (HTTP Basic):

```sh
# on
curl -u "$APP_SECURITY_USERNAME:$APP_SECURITY_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"configuredLevel":"INFO"}' http://localhost:8081/actuator/loggers/http.access
# off again (null clears the runtime level; http.access then inherits WARN from "http")
curl -u "$APP_SECURITY_USERNAME:$APP_SECURITY_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"configuredLevel":null}' http://localhost:8081/actuator/loggers/http.access
```

The change applies only to the instance whose 8081 port you call, so with several replicas call
each one's management port directly (not through a load balancer), and it lasts until the process
restarts. To start with it on, pass
`--logging.level.http.access=INFO` (or set `LOGGING_LEVEL_HTTP_ACCESS=INFO`). The same endpoint can
change any other logger's level too, for example `org.springframework.web` to `DEBUG`.

#### Elasticsearch mapping

Callers choose the header names, so indexed as they are, every new name becomes a new field and can
reach Elasticsearch's per-index field limit. [`deploy/elasticsearch/untitled-http-access.component-template.json`](deploy/elasticsearch/untitled-http-access.component-template.json)
maps `http.request.header` and `http.response.header` as `flattened` (one field each, still
searchable by header, e.g. `http.request.header.user-agent: curl*`). Install it and add
`untitled-http-access` to the `composed_of` list of the index template your logs use:

```sh
curl -X PUT -H 'Content-Type: application/json' "$ES_URL/_component_template/untitled-http-access" \
  --data-binary @deploy/elasticsearch/untitled-http-access.component-template.json
```

## Formatting

Java is formatted with [google-java-format](https://github.com/google/google-java-format) (Google
style: 2-space indent, 100 columns), with unused imports removed and annotations formatted (type
annotations kept on the same line as the type). `pom.xml`, `src/**/*.yaml`, `src/**/*.yml`,
`config/**/*.yaml`, `config/**/*.yml`, the root `*.md` files, `.editorconfig`, `.gitattributes`,
`.gitignore` and `.mvn/**/*.properties` get trailing whitespace trimmed, a final newline and
spaces instead of tabs. `docs/openapi.json` is not checked: it is generated and compared byte for
byte. If `verify` fails at `spotless:check`, fix the files with:

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
