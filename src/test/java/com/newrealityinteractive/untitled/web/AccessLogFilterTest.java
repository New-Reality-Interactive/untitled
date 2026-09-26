package com.newrealityinteractive.untitled.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AccessLogFilterTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger("http.access");
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final AccessLogFilter filter = new AccessLogFilter("");
  private Level originalLevel;

  @BeforeEach
  void captureAccessLog() {
    originalLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void restoreLogger() {
    logger.detachAppender(appender);
    logger.setLevel(originalLevel);
  }

  private WebTestClient client(WebHandler handler) {
    return WebTestClient.bindToWebHandler(handler).webFilter(filter).build();
  }

  private static WebHandler respondWith(HttpStatus status) {
    return exchange -> {
      exchange.getResponse().setStatusCode(status);
      return exchange.getResponse().setComplete();
    };
  }

  private Map<String, Object> onlyLine() {
    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    return fields(event);
  }

  private static Map<String, Object> fields(ILoggingEvent event) {
    List<KeyValuePair> pairs = event.getKeyValuePairs();
    return pairs.stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
  }

  @Test
  void logsMethodPathQueryStatusDurationAndRequestId() {
    String requestId =
        client(respondWith(HttpStatus.OK))
            .get()
            .uri("/api/v1/greetings?name=Ada")
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(Void.class)
            .getResponseHeaders()
            .getFirst(AccessLogFilter.REQUEST_ID_HEADER);

    Map<String, Object> fields = onlyLine();
    assertThat(fields)
        .containsEntry("http.request.method", "GET")
        .containsEntry("url.path", "/api/v1/greetings")
        .containsEntry("url.query", "name=Ada")
        .containsEntry("http.response.status_code", 200)
        .containsEntry("http.request.id", requestId);
    assertThat((Long) fields.get("event.duration")).isPositive();
    assertThat(appender.list.getFirst().getFormattedMessage())
        .matches("GET /api/v1/greetings\\?name=Ada 200 \\d+\\.\\d{3}ms X-Request-Id=" + requestId);
  }

  /** Runs the filter directly: the mock response consumes the whole body before completing. */
  private MockServerWebExchange respond(
      MockServerHttpRequest request, MediaType type, String... chunks) {
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    WebFilterChain writesChunks =
        filtered -> {
          filtered.getResponse().getHeaders().setContentType(type);
          return filtered
              .getResponse()
              .writeWith(
                  Flux.fromArray(chunks)
                      .map(
                          chunk ->
                              filtered
                                  .getResponse()
                                  .bufferFactory()
                                  .wrap(chunk.getBytes(StandardCharsets.UTF_8))));
        };
    StepVerifier.create(filter.filter(exchange, writesChunks)).verifyComplete();
    return exchange;
  }

  @Test
  void logsJsonResponseBodiesAndStillSendsThem() {
    MockServerWebExchange exchange =
        respond(
            MockServerHttpRequest.get("/api/v1/greetings?name=Ada")
                .header(AccessLogFilter.REQUEST_ID_HEADER, "ada-test-001")
                .build(),
            MediaType.APPLICATION_JSON,
            "{\"message\":",
            "\"Hello, Ada!\"}");

    StepVerifier.create(exchange.getResponse().getBodyAsString())
        .expectNext("{\"message\":\"Hello, Ada!\"}")
        .verifyComplete();
    assertThat(onlyLine())
        .containsEntry("http.response.body.content", "{\"message\":\"Hello, Ada!\"}")
        .containsEntry("http.response.body.bytes", 25L);
    assertThat(appender.list.getFirst().getFormattedMessage())
        .endsWith(" X-Request-Id=ada-test-001 response.body={\"message\":\"Hello, Ada!\"}");
  }

  @Test
  void keepsTheMessageShortWhenTheConsoleIsStructured() {
    WebTestClient.bindToWebHandler(respondWith(HttpStatus.OK))
        .webFilter(new AccessLogFilter("ecs"))
        .build()
        .get()
        .uri("/api/v1/greetings?name=Ada")
        .exchange();

    assertThat(onlyLine())
        .containsEntry("url.query", "name=Ada")
        .containsKey("http.request.id")
        .containsKey("event.duration");
    assertThat(appender.list.getFirst().getFormattedMessage())
        .isEqualTo("GET /api/v1/greetings 200");
  }

  /** Reads the request body and echoes it back as JSON, as a controller taking a body would. */
  private MockServerWebExchange echo(MockServerHttpRequest request) {
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    WebFilterChain echoes =
        filtered -> {
          filtered.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
          return filtered.getResponse().writeWith(filtered.getRequest().getBody());
        };
    StepVerifier.create(filter.filter(exchange, echoes)).verifyComplete();
    return exchange;
  }

  @Test
  void logsJsonRequestBodiesAndStillPassesThemOn() {
    MockServerWebExchange exchange =
        echo(
            MockServerHttpRequest.post("/actuator/loggers/http.access")
                .header(AccessLogFilter.REQUEST_ID_HEADER, "ada-test-001")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"configuredLevel\":\"INFO\"}"));

    StepVerifier.create(exchange.getResponse().getBodyAsString())
        .expectNext("{\"configuredLevel\":\"INFO\"}")
        .verifyComplete();
    assertThat(onlyLine())
        .containsEntry("http.request.body.content", "{\"configuredLevel\":\"INFO\"}")
        .containsEntry("http.request.body.bytes", 26L);
    assertThat(appender.list.getFirst().getFormattedMessage())
        .endsWith(
            " X-Request-Id=ada-test-001 request.body={\"configuredLevel\":\"INFO\"}"
                + " response.body={\"configuredLevel\":\"INFO\"}");
  }

  @Test
  void doesNotLogNonJsonRequestBodies() {
    echo(
        MockServerHttpRequest.post("/form")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("password=secret"));

    assertThat(onlyLine())
        .doesNotContainKey("http.request.body.content")
        .doesNotContainKey("http.request.body.bytes");
  }

  @Test
  void logsNoRequestBodyWhenTheApplicationDoesNotReadIt() {
    client(respondWith(HttpStatus.UNAUTHORIZED))
        .post()
        .uri("/actuator/loggers/http.access")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"configuredLevel\":\"INFO\"}")
        .exchange();

    assertThat(onlyLine()).doesNotContainKey("http.request.body.content");
  }

  @Test
  void logsJsonSuffixMediaTypesSuchAsProblemDetails() {
    respond(
        MockServerHttpRequest.get("/nope").build(),
        MediaType.APPLICATION_PROBLEM_JSON,
        "{\"status\":404}");

    assertThat(onlyLine()).containsEntry("http.response.body.content", "{\"status\":404}");
  }

  @Test
  void doesNotLogNonJsonResponseBodies() {
    respond(
        MockServerHttpRequest.get("/actuator/prometheus").build(),
        MediaType.TEXT_PLAIN,
        "jvm_threads_live 42");

    assertThat(onlyLine())
        .doesNotContainKey("http.response.body.content")
        .doesNotContainKey("http.response.body.bytes");
    assertThat(appender.list.getFirst().getFormattedMessage()).doesNotContain("body=");
  }

  @Test
  void cutsLargeJsonBodiesButCountsEveryByte() {
    String large = "[\"" + "x".repeat(AccessLogFilter.MAX_BODY_BYTES) + "\"]";
    respond(MockServerHttpRequest.get("/big").build(), MediaType.APPLICATION_JSON, large);

    Map<String, Object> fields = onlyLine();
    assertThat((String) fields.get("http.response.body.content"))
        .isEqualTo(large.substring(0, AccessLogFilter.MAX_BODY_BYTES));
    assertThat(fields).containsEntry("http.response.body.bytes", (long) large.length());
  }

  @Test
  void omitsQueryWhenThereIsNone() {
    client(respondWith(HttpStatus.OK)).get().uri("/actuator/health/liveness").exchange();

    assertThat(onlyLine())
        .doesNotContainKey("url.query")
        .containsEntry("url.path", "/actuator/health/liveness");
  }

  @Test
  void logsRejectedRequestsWithoutHeadersOrCookies() {
    client(respondWith(HttpStatus.UNAUTHORIZED))
        .get()
        .uri("/api/v1/greetings?name=Ada")
        .headers(headers -> headers.setBasicAuth("secret-user", "secret-password"))
        .header(HttpHeaders.PROXY_AUTHORIZATION, "Basic proxy-secret")
        .cookie("SESSION", "cookie-secret")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    assertThat(onlyLine()).containsEntry("http.response.status_code", 401);
    ILoggingEvent event = appender.list.getFirst();
    String everything = event.getFormattedMessage() + fields(event);
    assertThat(everything)
        .doesNotContain("Basic")
        .doesNotContain("c2VjcmV0") // base64 of "secret"
        .doesNotContain("secret");
  }

  @Test
  void logsServerErrorsAs500() {
    client(exchange -> Mono.error(new IllegalStateException("boom")))
        .get()
        .uri("/fails")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        .expectHeader()
        .exists(AccessLogFilter.REQUEST_ID_HEADER);

    assertThat(onlyLine()).containsEntry("http.response.status_code", 500);
  }

  @ResponseStatus(HttpStatus.CONFLICT)
  static class ConflictException extends RuntimeException {}

  @Test
  void logsErrorResponsesAndResponseStatusAnnotationsWithTheirStatus() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
    StepVerifier.create(
            filter.filter(
                exchange,
                ignored ->
                    Mono.error(new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT))))
        .verifyError();
    exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
    StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(new ConflictException())))
        .verifyError();

    assertThat(appender.list)
        .map(event -> fields(event).get("http.response.status_code"))
        .containsExactly(422, 409);
  }

  @Test
  void logsTheSentStatusWhenTheErrorComesAfterTheResponseIsCommitted() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
    WebFilterChain failsAfterCommit =
        committed -> {
          committed.getResponse().setStatusCode(HttpStatus.OK);
          return committed
              .getResponse()
              .setComplete()
              .then(Mono.error(new IllegalStateException("stream broke")));
        };

    StepVerifier.create(filter.filter(exchange, failsAfterCommit)).verifyError();

    assertThat(onlyLine()).containsEntry("http.response.status_code", 200);
  }

  @Test
  void logsResponseStatusExceptionsWithTheirStatusAndPropagatesTheError() {
    ResponseStatusException error = new ResponseStatusException(HttpStatus.NOT_FOUND);
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/nope"));

    StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(error)))
        .expectErrorSatisfies(thrown -> assertThat(thrown).isSameAs(error))
        .verify();

    assertThat(onlyLine()).containsEntry("http.response.status_code", 404);
  }

  @Test
  void logsCancelledExchanges() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/slow"));

    StepVerifier.create(filter.filter(exchange, ignored -> Mono.never())).thenCancel().verify();

    assertThat(onlyLine())
        .containsEntry("url.path", "/slow")
        .doesNotContainKey("http.response.status_code");
  }

  @Test
  void logsNothingWhenTheLoggerIsAboveInfoButStillReturnsARequestId() {
    logger.setLevel(Level.WARN);

    client(respondWith(HttpStatus.OK))
        .get()
        .uri("/api/v1/greetings?name=Ada")
        .exchange()
        .expectHeader()
        .exists(AccessLogFilter.REQUEST_ID_HEADER);

    assertThat(appender.list).isEmpty();
  }

  @Test
  void followsLevelChangesBetweenRequests() {
    WebTestClient client = client(respondWith(HttpStatus.OK));
    logger.setLevel(Level.WARN);
    client.get().uri("/first").exchange();
    logger.setLevel(Level.INFO);
    client.get().uri("/second").exchange();

    assertThat(onlyLine()).containsEntry("url.path", "/second");
  }

  @Test
  void reusesAWellFormedIncomingRequestId() {
    client(respondWith(HttpStatus.OK))
        .get()
        .uri("/")
        .header(AccessLogFilter.REQUEST_ID_HEADER, "abc-123_x.y:z")
        .exchange()
        .expectHeader()
        .valueEquals(AccessLogFilter.REQUEST_ID_HEADER, "abc-123_x.y:z");

    assertThat(onlyLine()).containsEntry("http.request.id", "abc-123_x.y:z");
  }

  @Test
  void replacesAMalformedOrOversizedIncomingRequestId() {
    for (String bad : List.of("has space", "new\nline", "a".repeat(129), "")) {
      MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.get("/").header(AccessLogFilter.REQUEST_ID_HEADER, bad));
      assertThat(AccessLogFilter.requestId(exchange.getRequest()))
          .as("replaces %s", bad)
          .isNotEqualTo(bad)
          .matches("[0-9a-f-]{36}");
    }
  }
}
