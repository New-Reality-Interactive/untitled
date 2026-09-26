package com.newrealityinteractive.untitled.greeting;

import com.newrealityinteractive.untitled.error.ValidationProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/greetings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Greetings", description = "Sample endpoint")
class GreetingController {

  static final int MAX_NAME_LENGTH = 100;

  private final GreetingService greetingService;

  GreetingController(GreetingService greetingService) {
    this.greetingService = greetingService;
  }

  @GetMapping
  @Operation(summary = "Greet someone by name")
  @ApiResponse(responseCode = "200", description = "The greeting")
  @ApiResponse(
      responseCode = "400",
      description = "The name is missing, blank or too long",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ValidationProblem.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials",
      content = @Content)
  Mono<Greeting> greet(
      @Parameter(description = "Name to greet", example = "Ada")
          @RequestParam
          @NotBlank @Size(min = 1, max = MAX_NAME_LENGTH) String name) {
    return greetingService.greet(name);
  }
}
