package com.newrealityinteractive.untitled.greeting;

import io.swagger.v3.oas.annotations.media.Schema;

/** A greeting addressed to a single name. */
@Schema(description = "A greeting addressed to a single name")
public record Greeting(
    @Schema(
            description = "The greeting text",
            example = "Hello, Ada!",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String message) {}
