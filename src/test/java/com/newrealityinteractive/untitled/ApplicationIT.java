package com.newrealityinteractive.untitled;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Runs against the application started by {@code spring-boot:start} (profile {@code it}) on the
 * ports reserved by build-helper, passed in as system properties by failsafe.
 */
class ApplicationIT {

  private static final String USERNAME = "it-user";
  private static final String PASSWORD = "it-password";

  private final WebTestClient app = client("it.app.port");
  private final WebTestClient management = client("it.management.port");

  private static WebTestClient client(String portProperty) {
    String port = System.getProperty(portProperty);
    assertThat(port).as("system property %s", portProperty).isNotBlank();
    return WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Test
  void livenessIsUp() {
    management
        .get()
        .uri("/actuator/health/liveness")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP");
  }

  @Test
  void readinessIsUpAndListsComponentsWithoutDetails() {
    management
        .get()
        .uri("/actuator/health/readiness")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP")
        .jsonPath("$.components.readinessState.status")
        .isEqualTo("UP")
        .jsonPath("$.components.greeting.status")
        .isEqualTo("UP")
        .jsonPath("$.components.greeting.details")
        .doesNotExist();
  }

  @Test
  void infoIsPublicAndReportsBuild() {
    management
        .get()
        .uri("/actuator/info")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.build.artifact")
        .isEqualTo("untitled");
  }

  @Test
  void prometheusIsPublicAndReportsHttpServerMetrics() {
    greetsAuthenticatedUser();

    management
        .get()
        .uri("/actuator/prometheus")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
        .expectBody(String.class)
        .value(
            body ->
                assertThat(body)
                    .contains("jvm_memory_used_bytes")
                    .containsPattern(
                        "http_server_requests_seconds_count\\{[^}]*"
                            + "application=\"untitled\"[^}]*uri=\"/api/v1/greetings\""));
  }

  @Test
  void sbomRequiresAuthentication() {
    management.get().uri("/actuator/sbom").exchange().expectStatus().isUnauthorized();
    management.get().uri("/actuator/sbom/application").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void sbomListsTheApplicationSbom() {
    management
        .get()
        .uri("/actuator/sbom")
        .headers(headers -> headers.setBasicAuth(USERNAME, PASSWORD))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.ids[?(@ == 'application')]")
        .exists();
  }

  @Test
  void sbomServesCycloneDxWithoutTestOrCompileOnlyDependencies() {
    management
        .mutate()
        // The SBOM (about 290 KB) is larger than the default 256 KB buffer limit.
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
        .build()
        .get()
        .uri("/actuator/sbom/application")
        .headers(headers -> headers.setBasicAuth(USERNAME, PASSWORD))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith("application/vnd.cyclonedx+json")
        .expectBody()
        .jsonPath("$.bomFormat")
        .isEqualTo("CycloneDX")
        .jsonPath("$.metadata.component.name")
        .isEqualTo("untitled")
        .jsonPath("$.components[?(@.name == 'spring-boot')]")
        .exists()
        .jsonPath(
            "$.components[?(@.name =~ /junit-.*|mockito-.*|assertj-.*|reactor-test|spring-test"
                + "|spring-security-test|spring-boot-configuration-processor/)]")
        .doesNotExist()
        .jsonPath("$.components[?(@.scope == 'optional')]")
        .doesNotExist();
  }

  @Test
  void unknownSbomIsNotFound() {
    management
        .get()
        .uri("/actuator/sbom/nope")
        .headers(headers -> headers.setBasicAuth(USERNAME, PASSWORD))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void unexposedActuatorEndpointsRequireAuthenticationAndStayUnexposed() {
    management.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
    management
        .get()
        .uri("/actuator/env")
        .headers(headers -> headers.setBasicAuth(USERNAME, PASSWORD))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void apiDocsArePublicOpenApi31WithGreetingAndBasicAuth() {
    app.get()
        .uri("/v3/api-docs")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody()
        .jsonPath("$.openapi")
        .value(version -> assertThat((String) version).startsWith("3.1"))
        .jsonPath("$.paths['/api/v1/greetings'].get")
        .exists()
        .jsonPath("$.components.securitySchemes.basicAuth.type")
        .isEqualTo("http")
        .jsonPath("$.components.securitySchemes.basicAuth.scheme")
        .isEqualTo("basic");
  }

  @Test
  void swaggerUiLoadsAnonymously() {
    String location =
        app.get()
            .uri("/swagger-ui.html")
            .exchange()
            .expectStatus()
            .is3xxRedirection()
            .returnResult(Void.class)
            .getResponseHeaders()
            .getLocation()
            .toString();

    app.get()
        .uri(location)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_HTML);
  }

  @Test
  void greetsAuthenticatedUser() {
    app.get()
        .uri("/api/v1/greetings?name=Ada")
        .headers(headers -> headers.setBasicAuth(USERNAME, PASSWORD))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectHeader()
        .doesNotExist(HttpHeaders.SET_COOKIE)
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("Hello, Ada!");
  }

  @Test
  void rejectsAnonymousGreeting() {
    app.get().uri("/api/v1/greetings?name=Ada").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void rejectsBlankName() {
    app.get()
        .uri("/api/v1/greetings?name=")
        .headers(headers -> headers.setBasicAuth(USERNAME, PASSWORD))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST)
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.errors")
        .isNotEmpty();
  }

  @Test
  void rejectsTooLongName() {
    app.get()
        .uri(uri -> uri.path("/api/v1/greetings").queryParam("name", "a".repeat(101)).build())
        .headers(headers -> headers.setBasicAuth(USERNAME, PASSWORD))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST)
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
  }
}
