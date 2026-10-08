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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NpgOrderBuildLoggingFilterTest {

    private static final String REQUEST_BODY = """
            {"merchantUrl":"https://ecommerce.pagopa.it","paymentSession":{"resultUrl":"https://ecommerce.pagopa.it/esito#clientId=IO&transactionId=tx&sessionToken=outcomeJwt","notificationUrl":"https://ecommerce.pagopa.it/notifications?sessionToken=notificationJwt","recurrence":{"contractId":"contract-123"}}}""";

    private static final String RESPONSE_BODY = """
            {"sessionId":"session-id","securityToken":"security-token","url":"https://npg/redirect"}""";

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
        webClient = WebClient.builder()
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
                        .setBody(RESPONSE_BODY)
        );

        StepVerifier.create(
                webClient.post()
                        .uri("/api/phoenix-0.0/psp/api/v1/orders/build")
                        .header("Correlation-Id", "correlation-id")
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
        assertEquals(2, logs.size());
        String requestLog = logs.get(0);
        String responseLog = logs.get(1);
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
    void shouldMaskSensitiveValues() {
        assertEquals(
                "{\"url\":\"https://x/esito#a=b&sessionToken=***\",\"securityToken\" : \"***\",\"contractId\":\"***\"}",
                NpgOrderBuildLoggingFilter.mask(
                        "{\"url\":\"https://x/esito#a=b&sessionToken=jwt.value\",\"securityToken\" : \"tok\",\"contractId\":\"c1\"}"
                )
        );
    }
}
