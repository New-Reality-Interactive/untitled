package com.newrealityinteractive.untitled.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.newrealityinteractive.untitled.Application;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.reactive.StandardReactiveWebEnvironment;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Starts the application with the {@code local} profile, which Spring Boot reads from {@code
 * ./config/application-local.yaml} (Surefire runs in the project root). The system environment is
 * replaced with one holding only the test's variables, so a developer's own environment cannot
 * change the result; test properties are not used because they would outrank environment variables.
 */
class LocalProfileTest {

  @Test
  void localProfileSuppliesCredentialsAndPlainTextLogs() {
    try (ConfigurableApplicationContext context = start(Map.of())) {
      ApiUserProperties properties = context.getBean(ApiUserProperties.class);
      assertThat(properties.username()).isEqualTo("local");
      assertThat(properties.password()).isEqualTo("local");
      assertThat(context.getEnvironment().getProperty("logging.structured.format.console"))
          .isEmpty();

      WebTestClient client = client(context);
      client
          .get()
          .uri("/api/v1/greetings?name=Ada")
          .headers(headers -> headers.setBasicAuth("local", "local"))
          .exchange()
          .expectStatus()
          .isOk();
      client.get().uri("/api/v1/greetings?name=Ada").exchange().expectStatus().isUnauthorized();
    }
  }

  @Test
  void localProfileFileIsNotOnTheClasspath() {
    assertThat(getClass().getResource("/application-local.yaml")).isNull();
  }

  @Test
  void environmentVariablesOverrideLocalCredentials() {
    try (ConfigurableApplicationContext context =
        start(Map.of("APP_SECURITY_USERNAME", "env-user", "APP_SECURITY_PASSWORD", "env-pw"))) {
      ApiUserProperties properties = context.getBean(ApiUserProperties.class);
      assertThat(properties.username()).isEqualTo("env-user");
      assertThat(properties.password()).isEqualTo("env-pw");
    }
  }

  /** Runs the app on free ports with a system environment of only the given variables. */
  private static ConfigurableApplicationContext start(Map<String, Object> environmentVariables) {

    ConfigurableEnvironment environment = new StandardReactiveWebEnvironment();
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));

    return new SpringApplicationBuilder(Application.class)
        .environment(environment)
        .profiles("local")
        .run("--server.port=0", "--management.server.port=0");
  }

  private static WebTestClient client(ConfigurableApplicationContext context) {
    String port = context.getEnvironment().getProperty("local.server.port");
    return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }
}
