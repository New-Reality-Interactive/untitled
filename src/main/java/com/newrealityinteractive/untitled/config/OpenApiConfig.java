package com.newrealityinteractive.untitled.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  static final String BASIC_AUTH = "basicAuth";
  static final String UNKNOWN_VERSION = "unknown";

  @Bean
  OpenAPI openApi(ObjectProvider<BuildProperties> buildProperties) {
    BuildProperties build = buildProperties.getIfAvailable();
    String version = build != null ? build.getVersion() : UNKNOWN_VERSION;
    return new OpenAPI()
        .info(
            new Info()
                .title("untitled API")
                .description("Reactive Spring Boot service")
                .version(version))
        // A fixed, relative server keeps the generated spec independent of the port it was
        // served from.
        .servers(List.of(new Server().url("/")))
        .components(
            new Components()
                .addSecuritySchemes(
                    BASIC_AUTH,
                    new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
        .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH));
  }
}
