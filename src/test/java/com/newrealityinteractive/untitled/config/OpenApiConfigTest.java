package com.newrealityinteractive.untitled.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenApiConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(OpenApiConfig.class);

  @Test
  void usesBuildVersionWhenBuildInfoIsPresent() {
    Properties build = new Properties();
    build.setProperty("version", "1.2.3");
    runner
        .withBean(BuildProperties.class, () -> new BuildProperties(build))
        .run(
            context -> {
              OpenAPI openApi = context.getBean(OpenAPI.class);
              assertThat(openApi.getInfo().getVersion()).isEqualTo("1.2.3");
            });
  }

  @Test
  void fallsBackWhenBuildInfoIsAbsent() {
    runner.run(
        context -> {
          OpenAPI openApi = context.getBean(OpenAPI.class);
          assertThat(openApi.getInfo().getVersion()).isEqualTo(OpenApiConfig.UNKNOWN_VERSION);
          assertThat(openApi.getServers())
              .singleElement()
              .satisfies(server -> assertThat(server.getUrl()).isEqualTo("/"));
          SecurityScheme basic =
              openApi.getComponents().getSecuritySchemes().get(OpenApiConfig.BASIC_AUTH);
          assertThat(basic.getType()).isEqualTo(SecurityScheme.Type.HTTP);
          assertThat(basic.getScheme()).isEqualTo("basic");
          assertThat(openApi.getSecurity())
              .singleElement()
              .satisfies(
                  requirement -> assertThat(requirement).containsKey(OpenApiConfig.BASIC_AUTH));
        });
  }
}
