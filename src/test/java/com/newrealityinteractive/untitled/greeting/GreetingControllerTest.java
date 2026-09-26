package com.newrealityinteractive.untitled.greeting;

import static org.assertj.core.api.Assertions.assertThat;

import com.newrealityinteractive.untitled.config.SecurityConfig;
import com.newrealityinteractive.untitled.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(
    controllers = GreetingController.class,
    properties = {"app.security.username=test-user", "app.security.password=test-password"})
@Import({SecurityConfig.class, ApiExceptionHandler.class, GreetingService.class})
class GreetingControllerTest {

  @Autowired private WebTestClient client;

  private WebTestClient authenticated() {
    return client
        .mutate()
        .defaultHeaders(headers -> headers.setBasicAuth("test-user", "test-password"))
        .build();
  }

  @Test
  void returnsGreetingForAuthenticatedUser() {
    authenticated()
        .get()
        .uri("/api/v1/greetings?name=Ada")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        // Stateless: authentication must not create a session.
        .expectHeader()
        .doesNotExist(HttpHeaders.SET_COOKIE)
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("Hello, Ada!");
  }

  @Test
  void rejectsAnonymousRequest() {
    client.get().uri("/api/v1/greetings?name=Ada").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void rejectsWrongPassword() {
    client
        .get()
        .uri("/api/v1/greetings?name=Ada")
        .headers(headers -> headers.setBasicAuth("test-user", "wrong"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void rejectsBlankName() {
    authenticated()
        .get()
        .uri("/api/v1/greetings?name= ")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(HttpStatus.BAD_REQUEST.value())
        .jsonPath("$.detail")
        .isEqualTo("Request validation failed")
        .jsonPath("$.errors[0]")
        .value(error -> assertThat((String) error).startsWith("name: "));
  }

  @Test
  void rejectsNameLongerThanLimit() {
    String tooLong = "a".repeat(GreetingController.MAX_NAME_LENGTH + 1);
    authenticated()
        .get()
        .uri(uri -> uri.path("/api/v1/greetings").queryParam("name", tooLong).build())
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.errors.length()")
        .isEqualTo(1);
  }

  @Test
  void acceptsNameAtLimit() {
    String atLimit = "a".repeat(GreetingController.MAX_NAME_LENGTH);
    authenticated()
        .get()
        .uri(uri -> uri.path("/api/v1/greetings").queryParam("name", atLimit).build())
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void rejectsMissingName() {
    authenticated()
        .get()
        .uri("/api/v1/greetings")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
  }
}
