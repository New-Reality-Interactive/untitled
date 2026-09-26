package com.newrealityinteractive.untitled.web;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Writes one access log line per exchange on both ports, as SLF4J key-value pairs with ECS names,
 * through the {@value #LOGGER_NAME} logger. The logger is at {@code WARN} by default; setting it to
 * {@code INFO} (for example through the actuator {@code loggers} endpoint) turns access logging on
 * without a restart. With a plain-text console, which drops key-value pairs, the message repeats
 * every field ({@code POST /path?query 200 12.345ms X-Request-Id=<id> request.body=<json>
 * response.body=<json>}); with a structured console format, which writes them as fields, it is only
 * {@code POST /path 200}.
 *
 * <p>JSON request and response bodies are logged, cut at {@value #MAX_BODY_BYTES} bytes; other
 * bodies (form data, Prometheus text, HTML, streams) are not. A request body is only seen when the
 * application reads it, so requests rejected before that (a 401, for example) log none. Headers,
 * cookies and the principal are never logged.
 *
 * <p>Every response carries an {@value #REQUEST_ID_HEADER} header: the caller's own value when it
 * is well formed, otherwise a generated UUID.
 */
@Component
public class AccessLogFilter implements WebFilter, Ordered {

  static final String LOGGER_NAME = "http.access";
  static final String REQUEST_ID_HEADER = "X-Request-Id";
  static final int MAX_BODY_BYTES = 8192;

  private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

  private final boolean plainText;

  AccessLogFilter(@Value("${logging.structured.format.console:}") String consoleFormat) {
    this.plainText = consoleFormat.isBlank();
  }

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
    RequestCapture request = new RequestCapture(exchange.getRequest());
    ResponseCapture response = new ResponseCapture(exchange.getResponse());
    return chain
        .filter(exchange.mutate().request(request).response(response).build())
        .doOnError(
            error ->
                write(exchange, requestId, start, errorStatus(exchange, error), request, response))
        .doFinally(
            signal -> {
              if (signal != SignalType.ON_ERROR) {
                write(
                    exchange,
                    requestId,
                    start,
                    exchange.getResponse().getStatusCode(),
                    request,
                    response);
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

  private void write(
      ServerWebExchange exchange,
      String requestId,
      long start,
      @Nullable HttpStatusCode status,
      RequestCapture requestBody,
      ResponseCapture responseBody) {
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
    String requestContent = requestBody.body.content();
    if (requestContent != null) {
      event =
          event
              .addKeyValue("http.request.body.content", requestContent)
              .addKeyValue("http.request.body.bytes", requestBody.body.bytes());
    }
    String responseContent = responseBody.body.content();
    if (responseContent != null) {
      event =
          event
              .addKeyValue("http.response.body.content", responseContent)
              .addKeyValue("http.response.body.bytes", responseBody.body.bytes());
    }
    String statusText = status != null ? String.valueOf(status.value()) : "-";
    if (!plainText) {
      event.log("{} {} {}", method, path, statusText);
      return;
    }
    event.log(
        "{} {}{} {} {}ms {}={}{}{}",
        method,
        path,
        query != null ? "?" + query : "",
        statusText,
        String.format(Locale.ROOT, "%.3f", duration / 1_000_000.0),
        REQUEST_ID_HEADER,
        requestId,
        requestContent != null ? " request.body=" + requestContent : "",
        responseContent != null ? " response.body=" + responseContent : "");
  }

  /** Copies the JSON request body as the application reads it. */
  static final class RequestCapture extends ServerHttpRequestDecorator {

    final BodyCopy body = new BodyCopy();

    RequestCapture(ServerHttpRequest delegate) {
      super(delegate);
    }

    @Override
    public Flux<DataBuffer> getBody() {
      return body.tap(getHeaders().getContentType(), super.getBody());
    }
  }

  /**
   * Copies the JSON response body as it is written; streams ({@code writeAndFlushWith}) are not.
   */
  static final class ResponseCapture extends ServerHttpResponseDecorator {

    final BodyCopy body = new BodyCopy();

    ResponseCapture(ServerHttpResponse delegate) {
      super(delegate);
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> content) {
      return super.writeWith(body.tap(getHeaders().getContentType(), content));
    }
  }

  /**
   * Copies the first {@value #MAX_BODY_BYTES} bytes of a JSON body as it passes, without consuming
   * it, and counts every byte.
   */
  static final class BodyCopy {

    private final ByteArrayOutputStream copy = new ByteArrayOutputStream();
    private long bytes;
    private boolean json;

    <T extends DataBuffer> Flux<T> tap(@Nullable MediaType type, Publisher<T> content) {
      json =
          type != null && (type.getSubtype().equals("json") || type.getSubtype().endsWith("+json"));
      return json ? Flux.from(content).doOnNext(this::copy) : Flux.from(content);
    }

    private synchronized void copy(DataBuffer buffer) {
      bytes += buffer.readableByteCount();
      try (DataBuffer.ByteBufferIterator views = buffer.readableByteBuffers()) {
        while (views.hasNext() && copy.size() < MAX_BODY_BYTES) {
          ByteBuffer view = views.next();
          byte[] chunk = new byte[Math.min(view.remaining(), MAX_BODY_BYTES - copy.size())];
          view.get(chunk);
          copy.writeBytes(chunk);
        }
      }
    }

    synchronized @Nullable String content() {
      return json && bytes > 0 ? copy.toString(StandardCharsets.UTF_8) : null;
    }

    synchronized long bytes() {
      return bytes;
    }
  }
}
