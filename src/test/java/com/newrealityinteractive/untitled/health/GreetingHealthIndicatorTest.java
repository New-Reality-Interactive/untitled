package com.newrealityinteractive.untitled.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newrealityinteractive.untitled.greeting.GreetingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GreetingHealthIndicatorTest {

  @Test
  void upWhenServiceGreets() {
    GreetingHealthIndicator indicator = new GreetingHealthIndicator(new GreetingService());

    StepVerifier.create(indicator.health())
        .assertNext(health -> assertThat(health.getStatus()).isEqualTo(Status.UP))
        .verifyComplete();
  }

  @Test
  void downWhenServiceFails() {
    GreetingService service = mock(GreetingService.class);
    when(service.greet(anyString())).thenReturn(Mono.error(new IllegalStateException("boom")));

    StepVerifier.create(new GreetingHealthIndicator(service).health())
        .assertNext(
            health -> {
              assertThat(health.getStatus()).isEqualTo(Status.DOWN);
              assertThat(health.getDetails()).containsKey("error");
            })
        .verifyComplete();
  }

  @Test
  void downWhenServiceReturnsNothing() {
    GreetingService service = mock(GreetingService.class);
    when(service.greet(anyString())).thenReturn(Mono.empty());

    StepVerifier.create(new GreetingHealthIndicator(service).health())
        .assertNext(health -> assertThat(health).isEqualTo(Health.down().build()))
        .verifyComplete();
  }
}
