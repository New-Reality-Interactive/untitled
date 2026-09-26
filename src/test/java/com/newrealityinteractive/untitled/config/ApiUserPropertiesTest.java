package com.newrealityinteractive.untitled.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class ApiUserPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(PropertiesConfig.class);

  @Test
  void bindsCredentials() {
    runner
        .withPropertyValues("app.security.username=user", "app.security.password=secret")
        .run(
            context -> {
              ApiUserProperties properties = context.getBean(ApiUserProperties.class);
              assertThat(properties.username()).isEqualTo("user");
              assertThat(properties.password()).isEqualTo("secret");
              assertThat(properties.toString()).doesNotContain("secret");
            });
  }

  @Test
  void failsStartupWhenCredentialsAreMissing() {
    runner.run(
        context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("app.security"));
  }

  @Test
  void failsStartupWhenUsernameIsBlank() {
    runner
        .withPropertyValues("app.security.username= ", "app.security.password=secret")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsStartupWhenPasswordIsBlank() {
    runner
        .withPropertyValues("app.security.username=user", "app.security.password= ")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ApiUserProperties.class)
  static class PropertiesConfig {}
}
