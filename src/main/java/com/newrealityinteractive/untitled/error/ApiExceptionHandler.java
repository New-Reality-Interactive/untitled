package com.newrealityinteractive.untitled.error;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Renders web and validation errors as RFC 9457 {@code application/problem+json} responses. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  static final String ERRORS_PROPERTY = "errors";

  @Override
  protected Mono<ResponseEntity<Object>> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      ServerWebExchange exchange) {
    ProblemDetail problem = ex.getBody();
    problem.setDetail("Request validation failed");
    List<String> errors =
        ex.getParameterValidationResults().stream()
            .flatMap(
                result ->
                    result.getResolvableErrors().stream()
                        .map(
                            error ->
                                result.getMethodParameter().getParameterName()
                                    + ": "
                                    + error.getDefaultMessage()))
            .sorted()
            .toList();
    problem.setProperty(ERRORS_PROPERTY, errors);
    return handleExceptionInternal(ex, problem, headers, status, exchange);
  }
}
