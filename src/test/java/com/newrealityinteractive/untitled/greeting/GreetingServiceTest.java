package com.newrealityinteractive.untitled.greeting;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class GreetingServiceTest {

  private final GreetingService service = new GreetingService();

  @Test
  void greetsByName() {
    StepVerifier.create(service.greet("Ada"))
        .expectNext(new Greeting("Hello, Ada!"))
        .verifyComplete();
  }
}
