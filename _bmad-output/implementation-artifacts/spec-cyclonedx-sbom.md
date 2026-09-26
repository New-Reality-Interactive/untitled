---
title: 'CycloneDX SBOM generated at build time and served by the actuator sbom endpoint'
type: 'feature'
created: '2026-09-26'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'b1cfb5b5e51e71ad2d476cb84acd8f8069e55577'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-observability-baseline.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Nothing records which libraries (and which versions) ship in the jar, so a new CVE cannot be checked against a running service without rebuilding its dependency tree by hand.

**Approach:** Activate Boot's pre-configured `cyclonedx-maven-plugin` execution so every build writes a CycloneDX JSON SBOM to `META-INF/sbom/application.cdx.json` inside the jar, and expose the actuator `sbom` endpoint on the management port so the running service serves its own SBOM. Cover it with an integration test and document it in the README.

## Boundaries & Constraints

**Always:** Plugin version comes from Boot (2.9.3, already the latest release on Maven Central); no version property is added unless Boot's version falls behind. Use the Boot parent's execution configuration (phase, output location, format) as-is. Every other actuator endpoint stays authenticated or unexposed. `./mvnw clean verify` stays green, including coverage, drift and Spotless.

**Never:** Committing a generated SBOM to the repo; test-scope dependencies in the SBOM; XML output; publishing the SBOM to an external service (Dependency-Track etc.); CI files; changing the access rules of existing endpoints.

**Decisions:** `/actuator/sbom` requires HTTP Basic credentials (anonymous → 401); `SecurityConfig` is unchanged, so the endpoint falls through to `anyExchange().authenticated()`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| List SBOMs | `GET /actuator/sbom` on 8081 with credentials | 200, JSON with `ids` containing `application` | anonymous → 401 |
| Fetch SBOM | `GET /actuator/sbom/application` on 8081 with credentials | 200, CycloneDX JSON (`bomFormat: CycloneDX`), components include `spring-boot` | anonymous → 401 |
| Unknown id | `GET /actuator/sbom/nope` with credentials | 404 | N/A |
| Packaged jar | `unzip -l target/untitled-*.jar` | contains `META-INF/sbom/application.cdx.json` | N/A |

</frozen-after-approval>

## Code Map

- `pom.xml` -- add `org.cyclonedx:cyclonedx-maven-plugin` to `<build><plugins>` with no version and no configuration; the parent's `pluginManagement` supplies the `makeAggregateBom` execution at `generate-resources` writing `${project.build.outputDirectory}/META-INF/sbom/application.cdx.json`. Boot's actuator `SbomEndpoint` auto-detects that classpath location, so no `management.endpoint.sbom.*` property is needed.
- `src/main/resources/application.yaml` -- `management.endpoints.web.exposure.include`: add `sbom`.
- `src/main/java/com/newrealityinteractive/untitled/config/SecurityConfig.java` -- no change; the authenticated fallback already covers the endpoint.
- `src/test/java/com/newrealityinteractive/untitled/ApplicationIT.java` -- follow the `prometheusIs...` / `unexposedActuatorEndpoints...` style; `USERNAME`/`PASSWORD` constants exist for Basic auth.
- `README.md` -- Endpoints table, paragraph under it, and `verify` step list.
- Do not touch: `docs/openapi.json` (management port, not in the API spec), `_bmad/`.

## Tasks & Acceptance

**Execution:**
- [x] `pom.xml` -- declare the cyclonedx plugin (Boot-managed version) -- activates the parent's SBOM execution.
- [x] `src/main/resources/application.yaml` -- expose `sbom` -- serves the embedded SBOM.
- [x] `ApplicationIT.java` -- tests for the list, fetch and access-rule scenarios in the matrix -- proves the endpoint and the rule.
- [x] `README.md` -- endpoint row, where the SBOM lives in the jar, access rule, and that `verify` generates it -- keeps docs matching the build.

**Acceptance Criteria:**
- Given `./mvnw clean package -DskipTests`, when the jar is listed, then it contains `META-INF/sbom/application.cdx.json` whose components exclude test-scope artifacts (e.g. no `junit-jupiter`).
- Given the running app, when `/actuator/env` is requested with credentials, then it is still 404.

## Implementation Notes

- `pom.xml`: `cyclonedx-maven-plugin` declared with no version (Boot 2.9.3 = latest) and no execution config; the parent's `makeAggregateBom` execution writes `target/classes/META-INF/sbom/application.cdx.json`, and Boot's repackage adds `Sbom-Location`/`Sbom-Format` to the jar manifest.
- `application.yaml`: `sbom` added to the exposure list. `SecurityConfig` unchanged.
- `ApplicationIT`: anonymous 401 on list and fetch; authenticated list contains `application`; fetch is `application/vnd.cyclonedx+json`, `bomFormat: CycloneDX`, lists `spring-boot`, no test-scope or optional components; unknown id 404. The fetch test raises the WebTestClient buffer to 4 MB (the SBOM is about 290 KB, over the 256 KB default).
- Review decision (human, 2026-09-26): the `spring-boot-configuration-processor` `<dependency>` was removed. It was listed in the SBOM as `optional` although the jar does not ship it, and the cyclonedx plugin's `excludeArtifactId` only excludes reactor modules. The processor still runs from `annotationProcessorPaths`; `spring-configuration-metadata.json` is still generated.
- README: SBOM section, two endpoint rows, SBOM step in the `verify` list.
- `./mvnw -B clean verify`: BUILD SUCCESS, 18 unit + 15 IT, coverage met, no OpenAPI drift, Spotless clean. SBOM: 110 components, none test-scope or optional.

## Verification

**Commands:**
- `./mvnw -B clean verify` -- expected: BUILD SUCCESS, all unit and IT tests pass, coverage met, no OpenAPI drift, Spotless clean.
- `unzip -p target/untitled-*.jar META-INF/sbom/application.cdx.json | jq -r '.components[].name' | grep -c junit` -- expected: `0`.
