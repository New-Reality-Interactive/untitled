package com.newrealityinteractive.untitled.health;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.newrealityinteractive.untitled.greeting.GreetingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Full application on random ports with the greeting component forced DOWN. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "app.security.username=test-user",
      "app.security.password=test-password"
    })
class ReadinessDownTest {

  @LocalManagementPort private int managementPort;

  @MockitoBean private GreetingService greetingService;

  @Test
  void readinessIsUnavailableWhenGreetingComponentIsDown() {
    given(greetingService.greet(anyString()))
        .willReturn(Mono.error(new IllegalStateException("down")));

    WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + managementPort)
        .build()
        .get()
        .uri("/actuator/health/readiness")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("DOWN")
        .jsonPath("$.components.greeting.status")
        .isEqualTo("DOWN")
        .jsonPath("$.components.greeting.details")
        .doesNotExist();
  }
}
