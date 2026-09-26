package com.newrealityinteractive.untitled.greeting;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Builds greetings. Pure computation, so it is safe to call on reactor threads. */
@Service
public class GreetingService {

  public Mono<Greeting> greet(String name) {
    return Mono.fromSupplier(() -> new Greeting("Hello, " + name + "!"));
  }
}
