- source_spec: `/Users/bpmericle/Work/new-reality-interactive/github/untitled/_bmad-output/implementation-artifacts/spec-reactive-spring-boot-scaffold.md`
  summary: Generate a CycloneDX SBOM during the build (Boot-managed cyclonedx-maven-plugin, pinned to latest) and expose it via the actuator sbom endpoint.
  evidence: Split at user request to keep the scaffold spec under the 1600-token scope guideline; supply-chain tooling is separately shippable.
- source_spec: `/Users/bpmericle/Work/new-reality-interactive/github/untitled/_bmad-output/implementation-artifacts/spec-reactive-spring-boot-scaffold.md`
  summary: Add an application-local.yaml profile convention for developer runs (local credentials, human-readable logs) and document it in the README.
  evidence: Split at user request to keep the scaffold spec under the 1600-token scope guideline; developer-convenience profile is separately shippable.
- source_spec: none
  summary: Add HTTP request/response logging for every endpoint on both ports (method, path, query, status, duration, correlation ID, as ECS JSON), with a flag that turns it on or off while the service is running, with no restart.
  evidence: User request, 2026-09-26, while testing by hand. Reactor Netty's access log (`-Dreactor.netty.http.server.accessLogEnabled=true`) and WebFlux DEBUG logging both work, but the access-log flag is read only at startup. So a runtime toggle needs either a logging `WebFilter` whose logger level is changed through the actuator `loggers` endpoint (not exposed yet; it would need to be authenticated) or another runtime-refreshable flag. Must never log `Authorization` or other credential headers.
