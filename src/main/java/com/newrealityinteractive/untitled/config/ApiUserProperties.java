package com.newrealityinteractive.untitled.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Credentials for the single HTTP Basic API user. Supplied through configuration or the {@code
 * APP_SECURITY_USERNAME} / {@code APP_SECURITY_PASSWORD} environment variables; startup fails when
 * either is missing.
 *
 * @param username name of the HTTP Basic API user
 * @param password password of the HTTP Basic API user
 */
@Validated
@ConfigurationProperties("app.security")
public record ApiUserProperties(@NotBlank String username, @NotBlank String password) {

  @Override
  public String toString() {
    return "ApiUserProperties[username=" + username + ", password=******]";
  }
}
