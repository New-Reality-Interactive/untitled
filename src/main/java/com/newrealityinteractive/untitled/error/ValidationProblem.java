package com.newrealityinteractive.untitled.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;

/**
 * Documents the {@code application/problem+json} body of a validation failure as it is actually
 * serialized: RFC 9457 members plus the flat {@code errors} extension set by {@link
 * ApiExceptionHandler}. Used only for the OpenAPI contract.
 */
@Schema(
    name = "ValidationProblem",
    description = "RFC 9457 problem detail for a request that failed validation")
public record ValidationProblem(
    @Schema(example = "about:blank") URI type,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Bad Request") String title,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "400") int status,
    @Schema(example = "Request validation failed") String detail,
    @Schema(example = "/api/v1/greetings") URI instance,
    @Schema(
            description =
                "One entry per violated constraint, as \"parameter: message\"; absent when the "
                    + "request is rejected before validation (for example, a missing parameter)",
            example = "[\"name: must not be blank\"]")
        List<String> errors) {}
