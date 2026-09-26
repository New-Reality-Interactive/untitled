package com.newrealityinteractive.untitled.web;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Writes one access log line per exchange on both ports, as SLF4J key-value pairs with ECS names,
 * through the {@value #LOGGER_NAME} logger. The logger is at {@code WARN} by default; setting it to
 * {@code INFO} (for example through the actuator {@code loggers} endpoint) turns access logging on
 * without a restart. The message repeats every field ({@code GET /path?query 200 12.345ms
 * id=<request id>}) so plain-text console output, which drops key-value pairs, shows them too.
 * Headers, cookies, bodies and the principal are never logged.
 *
 * <p>Every response carries an {@value #REQUEST_ID_HEADER} header: the caller's own value when it
 * is well formed, otherwise a generated UUID.
 */
@Component
public class AccessLogFilter implements WebFilter, Ordered {

  static final String LOGGER_NAME = "http.access";
  static final String REQUEST_ID_HEADER = "X-Request-Id";

  private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

  @Override
  public int getOrder() {
    // Before Spring Security, so rejected requests are logged too.
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String requestId = requestId(exchange.getRequest());
    exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
    if (!log.isInfoEnabled()) {
      return chain.filter(exchange);
    }
    long start = System.nanoTime();
    return chain
        .filter(exchange)
        .doOnError(error -> write(exchange, requestId, start, errorStatus(exchange, error)))
        .doFinally(
            signal -> {
              if (signal != SignalType.ON_ERROR) {
                write(exchange, requestId, start, exchange.getResponse().getStatusCode());
              }
            });
  }

  static String requestId(ServerHttpRequest request) {
    String incoming = request.getHeaders().getFirst(REQUEST_ID_HEADER);
    return incoming != null && VALID_REQUEST_ID.matcher(incoming).matches()
        ? incoming
        : UUID.randomUUID().toString();
  }

  /**
   * An error that leaves this filter before the response is committed is rendered by the web
   * exception handlers afterwards, so the response has no status yet: use the one they will pick.
   */
  private static @Nullable HttpStatusCode errorStatus(ServerWebExchange exchange, Throwable error) {
    if (exchange.getResponse().isCommitted()) {
      return exchange.getResponse().getStatusCode();
    }
    if (error instanceof ErrorResponse errorResponse) {
      return errorResponse.getStatusCode();
    }
    ResponseStatus annotation =
        AnnotatedElementUtils.findMergedAnnotation(error.getClass(), ResponseStatus.class);
    return annotation != null ? annotation.code() : HttpStatus.INTERNAL_SERVER_ERROR;
  }

  private static void write(
      ServerWebExchange exchange, String requestId, long start, @Nullable HttpStatusCode status) {
    ServerHttpRequest request = exchange.getRequest();
    String method = request.getMethod().name();
    String path = request.getPath().value();
    long duration = System.nanoTime() - start;
    LoggingEventBuilder event =
        log.atInfo()
            .addKeyValue("http.request.method", method)
            .addKeyValue("url.path", path)
            .addKeyValue("http.request.id", requestId)
            .addKeyValue("event.duration", duration);
    String query = request.getURI().getRawQuery();
    if (query != null) {
      event = event.addKeyValue("url.query", query);
    }
    if (status != null) {
      event = event.addKeyValue("http.response.status_code", status.value());
    }
    event.log(
        "{} {}{} {} {}ms id={}",
        method,
        path,
        query != null ? "?" + query : "",
        status != null ? status.value() : "-",
        String.format(Locale.ROOT, "%.3f", duration / 1_000_000.0),
        requestId);
  }
}
