package it.pagopa.transactions.utils;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NpgOrderBuildLoggingFilterTest {

    private static final String REQUEST_BODY = """
            {"merchantUrl":"https://ecommerce.pagopa.it","paymentSession":{"resultUrl":"https://ecommerce.pagopa.it/esito#clientId=IO&transactionId=tx&sessionToken=outcomeJwt","notificationUrl":"https://ecommerce.pagopa.it/notifications?sessionToken=notificationJwt","recurrence":{"contractId":"contract-123"}}}""";

    private static final String RESPONSE_BODY = """
            {"sessionId":"session-id","securityToken":"security-token","url":"https://npg/redirect"}""";

    private static final String API_KEY = "npg-api-key-0123456789abcdef";

    private MockWebServer mockWebServer;
    private ListAppender<ILoggingEvent> logAppender;
    private WebClient webClient;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(NpgOrderBuildLoggingFilter.class)).addAppender(logAppender);
        HttpClient httpClient = HttpClient.create()
                .doAfterRequest(NpgOrderBuildLoggingFilter::logWireRequest)
                .doOnResponse(NpgOrderBuildLoggingFilter::logWireResponse);
        webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(mockWebServer.url("/").toString())
                .filter(new NpgOrderBuildLoggingFilter())
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        ((Logger) LoggerFactory.getLogger(NpgOrderBuildLoggingFilter.class)).detachAppender(logAppender);
        mockWebServer.shutdown();
    }

    @Test
    void shouldLogMaskedOrderBuildRequestAndResponseWithoutAlteringBodies() throws InterruptedException {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .setHeader("X-Npg-Trace", "npg-trace")
                        .setBody(RESPONSE_BODY)
        );

        StepVerifier.create(
                webClient.post()
                        .uri("/api/phoenix-0.0/psp/api/v1/orders/build")
                        .header("Correlation-Id", "correlation-id")
                        .header("X-API-KEY", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(REQUEST_BODY)
                        .retrieve()
                        .bodyToMono(String.class)
        )
                .expectNext(RESPONSE_BODY)
                .verifyComplete();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertEquals(REQUEST_BODY, recordedRequest.getBody().readUtf8());

        List<String> logs = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        String expectedApiKeyFingerprint = NpgOrderBuildLoggingFilter.fingerprint(API_KEY);
        String requestHeadersLog = findLog(logs, "NPG order/build request - correlationId: [correlation-id], method:");
        String requestLog = findLog(logs, "NPG order/build request - correlationId: [correlation-id], body:");
        String wireRequestLog = findLog(logs, "NPG order/build wire request - correlationId: [correlation-id]");
        String wireResponseLog = findLog(logs, "NPG order/build wire response - correlationId: [correlation-id]");
        String responseHeadersLog = findLog(
                logs,
                "NPG order/build response - correlationId: [correlation-id], status: [200], headers:"
        );
        String responseLog = findLog(
                logs,
                "NPG order/build response - correlationId: [correlation-id], status: [200], body:"
        );
        assertEquals(6, logs.size());
        assertTrue(requestHeadersLog.contains("method: [POST]"));
        assertTrue(requestHeadersLog.contains("/api/phoenix-0.0/psp/api/v1/orders/build"));
        assertTrue(requestHeadersLog.contains("Correlation-Id: [correlation-id]"));
        assertTrue(requestHeadersLog.contains("X-API-KEY: [" + expectedApiKeyFingerprint + "]"));
        assertTrue(wireRequestLog.contains("X-API-KEY: " + expectedApiKeyFingerprint));
        assertTrue(wireRequestLog.contains("host: "));
        assertTrue(wireRequestLog.contains("user-agent: "));
        assertTrue(wireRequestLog.contains("content-length: "));
        assertTrue(wireResponseLog.contains("status: [200 OK]"));
        assertTrue(wireResponseLog.contains("X-Npg-Trace: npg-trace"));
        assertTrue(responseHeadersLog.contains("X-Npg-Trace: [npg-trace]"));
        logs.forEach(log -> assertFalse(log.contains("npg-api-key-0123456789abcdef")));
        assertTrue(requestLog.startsWith("NPG order/build request - correlationId: [correlation-id]"));
        assertTrue(requestLog.contains("\"merchantUrl\":\"https://ecommerce.pagopa.it\""));
        assertTrue(requestLog.contains("sessionToken=***"));
        assertTrue(requestLog.contains("\"contractId\":\"***\""));
        assertFalse(requestLog.contains("outcomeJwt"));
        assertFalse(requestLog.contains("notificationJwt"));
        assertFalse(requestLog.contains("contract-123"));
        assertTrue(responseLog.startsWith("NPG order/build response - correlationId: [correlation-id], status: [200]"));
        assertTrue(responseLog.contains("\"sessionId\":\"session-id\""));
        assertTrue(responseLog.contains("\"securityToken\":\"***\""));
        assertFalse(responseLog.contains("security-token"));
    }

    @Test
    void shouldNotLogOtherNpgApis() throws InterruptedException {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .setBody(RESPONSE_BODY)
        );

        StepVerifier.create(
                webClient.post()
                        .uri("/api/phoenix-0.0/psp/api/v1/build/confirmPayment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(REQUEST_BODY)
                        .retrieve()
                        .bodyToMono(String.class)
        )
                .expectNext(RESPONSE_BODY)
                .verifyComplete();

        assertEquals(REQUEST_BODY, mockWebServer.takeRequest().getBody().readUtf8());
        assertTrue(logAppender.list.isEmpty());
    }

    @Test
    void shouldFingerprintSecretWithoutDisclosingIt() {
        String fingerprint = NpgOrderBuildLoggingFilter.fingerprint(API_KEY);

        assertTrue(fingerprint.startsWith("{length=28, first4=npg-, last4=cdef, sha256="));
        assertTrue(fingerprint.contains("leadingOrTrailingWhitespace=false"));
        assertTrue(fingerprint.contains("nonPrintableChars=false"));
        assertTrue(fingerprint.contains("sha256=" + sha256Hex(API_KEY)));
        assertFalse(fingerprint.contains("0123456789abcdef"));
        assertTrue(
                NpgOrderBuildLoggingFilter.fingerprint(" " + API_KEY + "\n")
                        .contains("leadingOrTrailingWhitespace=true, nonPrintableChars=true")
        );
        assertEquals(
                "{length=5, first4=***, last4=***, sha256=%s, leadingOrTrailingWhitespace=false, nonPrintableChars=false}"
                        .formatted(sha256Hex("short")),
                NpgOrderBuildLoggingFilter.fingerprint("short")
        );
    }

    private static String findLog(
                                  List<String> logs,
                                  String prefix
    ) {
        return logs.stream()
                .filter(log -> log.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No log starting with: " + prefix + " in " + logs));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void shouldMaskSensitiveValues() {
        assertEquals(
                "{\"url\":\"https://x/esito#a=b&sessionToken=***\",\"securityToken\" : \"***\",\"contractId\":\"***\"}",
                NpgOrderBuildLoggingFilter.mask(
                        "{\"url\":\"https://x/esito#a=b&sessionToken=jwt.value\",\"securityToken\" : \"tok\",\"contractId\":\"c1\"}"
                )
        );
    }
}
