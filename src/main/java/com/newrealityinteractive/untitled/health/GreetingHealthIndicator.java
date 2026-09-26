package com.newrealityinteractive.untitled.health;

import com.newrealityinteractive.untitled.greeting.GreetingService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reports the {@code greeting} readiness component: UP when the greeting service can produce a
 * greeting.
 */
@Component
public class GreetingHealthIndicator implements ReactiveHealthIndicator {

  static final String PROBE_NAME = "readiness-probe";

  private final GreetingService greetingService;

  public GreetingHealthIndicator(GreetingService greetingService) {
    this.greetingService = greetingService;
  }

  @Override
  public Mono<Health> health() {
    return greetingService
        .greet(PROBE_NAME)
        .map(greeting -> Health.up().build())
        .defaultIfEmpty(Health.down().build())
        .onErrorResume(ex -> Mono.just(Health.down(ex).build()));
  }
}
