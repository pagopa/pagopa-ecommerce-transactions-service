package it.pagopa.transactions.controllers.v2;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.ReactiveUniqueIdUtils;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.model.CtFaultBean;
import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument;
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.services.v2.TransactionsService;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.data.redis.AutoConfigureDataRedis;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@WebFluxTest(it.pagopa.transactions.controllers.v2.TransactionsController.class)
@TestPropertySource(locations = "classpath:application-tests.properties")
@AutoConfigureDataRedis
class TransactionsControllerTest {

    @InjectMocks
    private it.pagopa.transactions.controllers.v2.TransactionsController transactionsController = new it.pagopa.transactions.controllers.v2.TransactionsController();

    @MockitoBean
    @Qualifier(TransactionsService.QUALIFIER_NAME)
    private TransactionsService transactionsService;

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private TransactionsUtils transactionsUtils;

    @MockitoBean
    private UUIDUtils uuidUtils;

    @MockitoBean
    private ReactiveUniqueIdUtils uniqueIdUtils;

    @MockitoBean
    private OpenTelemetryUtils openTelemetryUtils;

    @MockitoBean
    private it.pagopa.transactions.controllers.v1.TransactionsController transactionsControllerV1;

    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
            Map.of("circuit-breaker-test", CircuitBreakerConfig.ofDefaults())
    );

    @Test
    void testConflictStatusExceptionHandler() {
        final TransactionId transactionId = new TransactionId(UUID.randomUUID());

        ResponseEntity responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Transaction already processed")
                        .detail("Transaction for RPT id '' has been already processed"),
                HttpStatus.CONFLICT
        );
        ConflictStatusException exception = new ConflictStatusException(transactionId);

        ResponseEntity<ProblemJsonDto> response = transactionsController.alreadyProcessedHandler(exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void testBadGatewayExceptionHandler() {
        ResponseEntity<ProblemJsonDto> responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(502)
                        .title("Bad gateway")
                        .detail(null),
                HttpStatus.BAD_GATEWAY
        );
        BadGatewayException exception = new BadGatewayException("", HttpStatus.BAD_REQUEST);
        ResponseEntity<ProblemJsonDto> response = transactionsController.badGatewayHandler(exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void testGatewayTimeoutExceptionHandler() {
        ResponseEntity<ProblemJsonDto> responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(504)
                        .title("Gateway timeout")
                        .detail(null),
                HttpStatus.GATEWAY_TIMEOUT
        );
        GatewayTimeoutException exception = new GatewayTimeoutException();

        ResponseEntity<ProblemJsonDto> response = transactionsController.gatewayTimeoutHandler(exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void testDigitalStampNotAllowedForClientExceptionHandler() {
        ResponseEntity<ValidationFaultPaymentDataErrorProblemJsonDto> responseCheck = new ResponseEntity<>(
                new ValidationFaultPaymentDataErrorProblemJsonDto()
                        .title("Payment Status Fault")
                        .faultCodeCategory(
                                ValidationFaultPaymentDataErrorProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_DATA_ERROR
                        )
                        .faultCodeDetail(ValidationFaultPaymentDataErrorDto.PPT_DOMINIO_SCONOSCIUTO),
                HttpStatus.NOT_FOUND
        );
        DigitalStampNotAllowedForClientException exception = new DigitalStampNotAllowedForClientException("IO");

        ResponseEntity<ValidationFaultPaymentDataErrorProblemJsonDto> response = transactionsController
                .digitalStampNotAllowedHandler(exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
        assertEquals(responseCheck.getBody(), response.getBody());
    }

    @Test
    void shouldReturnErrorCircuitBreakerOpen() {

        ResponseEntity error = transactionsController.openStateHandler(
                CallNotPermittedException.createCallNotPermittedException(
                        circuitBreakerRegistry.circuitBreaker("circuit-breaker-test")
                )
        ).block();

        // Verify status code and response
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
    }

    @Test
    void shouldReturnResponseEntityWithGenericGatewayFault() {
        CtFaultBean faultBean = faultBeanWithCode("UNKNOWN_ERROR");
        ResponseEntity<ProblemJsonDto> responseEntity = (ResponseEntity<ProblemJsonDto>) transactionsController
                .nodoErrorHandler(new NodoErrorException(faultBean));

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
    }

    @Test
    void shouldReturnResponseEntityWithBadRequest() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .validationExceptionHandler(new InvalidRequestException("Some message"));
        assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        assertEquals("Invalid request: Some message", responseEntity.getBody().getDetail());
    }

    @Test
    void shouldReturnResponseEntityWithNotImplemented() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .notImplemented(new NotImplementedException("Method not implemented"));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, responseEntity.getStatusCode());
        assertEquals("Method not implemented", responseEntity.getBody().getDetail());
    }

    @Test
    void shouldReturnResponseEntityWithInternalServerErrorForErrorGeneratingJwtToken() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .jwtTokenGenerationError(new JwtIssuerResponseException(HttpStatus.BAD_GATEWAY, "jwt issuer error"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, responseEntity.getStatusCode());
        assertEquals("Internal server error: cannot generate JWT token", responseEntity.getBody().getDetail());

    }

    @Test
    void shouldReturnResponseEntityWithInvalidNodoResponseReceivedError() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .invalidNodoResponse(new InvalidNodoResponseException("Invalid payment token received"));

        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
        assertEquals(
                "Invalid payment token received",
                responseEntity.getBody().getDetail()
        );
    }

    @Test
    void shouldHandleGetTransactionSuccessfully() {
        String transactionId = it.pagopa.ecommerce.commons.v2.TransactionTestUtils.TRANSACTION_ID;
        TransactionInfoDto expectedResponse = new TransactionInfoDto()
                .gatewayInfo(
                        new TransactionInfoGatewayInfoDto()
                                .gateway("NPG")
                                .authorizationCode("authorizationCode")
                                .authorizationStatus("authorizationStatus")
                                .errorCode("errorCode")
                )
                .nodeInfo(
                        new TransactionInfoNodeInfoDto()
                                .closePaymentResultError(
                                        new TransactionInfoNodeInfoClosePaymentResultErrorDto()
                                                .description("errorDescription")
                                                .statusCode(BigDecimal.valueOf(404))
                                )
                                .sendPaymentResultOutcome(TransactionInfoNodeInfoDto.SendPaymentResultOutcomeEnum.KO)
                )
                .transactionId(transactionId)
                .idCart("idCart")
                .clientId(TransactionInfoDto.ClientIdEnum.CHECKOUT)
                .feeTotal(200)
                .status(TransactionStatusDto.CLOSED)
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .rptId("rptId")
                );

        Mockito.when(transactionsService.getTransactionInfo(any(), any()))
                .thenReturn(Mono.just(expectedResponse));
        webTestClient.get()
                .uri("/v2/transactions/{transactionId}", Map.of("transactionId", transactionId))
                .header("X-Client-Id", "CHECKOUT")
                .header("x-correlation-id", UUID.randomUUID().toString())
                .header("x-api-key", "primary-key")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.OK)
                .expectBody(TransactionInfoDto.class)
                .value(response -> {
                    assertEquals(
                            expectedResponse,
                            response
                    );
                });
        verify(transactionsService, times(1)).getTransactionInfo(transactionId, null);

    }

    public static Stream<Arguments> patchAuthRequestProxyTestMethodSource() {
        return Stream.of(
                Arguments.of(
                        new it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto()
                                .outcomeGateway(
                                        new it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto()
                                                .authorizationCode("authorizationCode")
                                                .operationResult(
                                                        it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED
                                                )
                                                .orderId("orderId")
                                                .operationId("operationId")
                                                .authorizationCode("authorizationCode")
                                                .errorCode("errorCode")
                                                .paymentEndToEndId("paymentEndToEndId")
                                                .rrn("rrn")
                                                .validationServiceId("validationServiceId")
                                ).timestampOperation(ZonedDateTime.now(ZoneOffset.UTC).toOffsetDateTime())
                ),
                Arguments.of(
                        new it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto()
                                .outcomeGateway(
                                        new it.pagopa.generated.transactions.server.model.OutcomeRedirectGatewayDto()
                                                .paymentGatewayType("REDIRECT")
                                                .pspTransactionId("pspTransactionId")
                                                .outcome(
                                                        it.pagopa.generated.transactions.server.model.AuthorizationOutcomeDto.OK
                                                )
                                                .pspId("pspId")
                                                .authorizationCode("authorizationCode")
                                                .errorCode("errorCode")

                                ).timestampOperation(ZonedDateTime.now(ZoneOffset.UTC).toOffsetDateTime())
                )
        );
    }

    @ParameterizedTest
    @MethodSource("patchAuthRequestProxyTestMethodSource")
    void shouldProxyPatchAuthRequestToV1Controller(
                                                   it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto expectedRequest
    ) {
        /* preconditions */
        TransactionId transactionId = new TransactionId(
                it.pagopa.ecommerce.commons.v2.TransactionTestUtils.TRANSACTION_ID
        );

        it.pagopa.generated.transactions.server.model.TransactionInfoDto transactionInfoDto = new it.pagopa.generated.transactions.server.model.TransactionInfoDto()
                .status(it.pagopa.generated.transactions.server.model.TransactionStatusDto.CLOSURE_REQUESTED)
                .transactionId(transactionId.value());
        Mockito.when(transactionsControllerV1.handleUpdateAuthorizationRequest(any(), any(), any()))
                .thenReturn(Mono.just(transactionInfoDto));
        /* test */
        Hooks.onOperatorDebug();
        webTestClient.patch()
                .uri("/v2/transactions/{transactionId}/auth-requests", transactionId.value())
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", "primary-key")
                .bodyValue(expectedRequest)
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectBody(UpdateAuthorizationResponseDto.class)
                .value(r -> assertEquals(TransactionStatusDto.CLOSURE_REQUESTED, r.getStatus()));
        verify(transactionsControllerV1, times(1))
                .handleUpdateAuthorizationRequest(eq(transactionId), eq(expectedRequest), any());
    }

    @Test
    void shouldReturn422ForNoLockAcquiredOnPatchAuthRequest() {
        /* preconditions */
        TransactionId transactionId = new TransactionId(
                it.pagopa.ecommerce.commons.v2.TransactionTestUtils.TRANSACTION_ID
        );
        ExclusiveLockDocument lockDocument = new ExclusiveLockDocument(
                "PATCH-auth-request-%s".formatted(transactionId.value()),
                "transactions-service"
        );
        it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto updateAuthorizationRequestDto = new it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto()
                                .authorizationCode("authorizationCode")
                                .operationResult(
                                        it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED
                                )
                ).timestampOperation(java.time.OffsetDateTime.now());

        Mockito.when(transactionsControllerV1.handleUpdateAuthorizationRequest(any(), any(), any()))
                .thenReturn(Mono.error(new LockNotAcquiredException(transactionId, lockDocument)));
        /* test */
        webTestClient.patch()
                .uri("/v2/transactions/{transactionId}/auth-requests", transactionId.value())
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", "primary-key")
                .bodyValue(updateAuthorizationRequestDto)
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectBody(ProblemJsonDto.class)
                .value(
                        p -> {
                            assertEquals(422, p.getStatus());
                            assertEquals(
                                    "Lock not acquired for transaction with id: [%1$s] and locking key: [PATCH-auth-request-%1$s]"
                                            .formatted(transactionId.value()),
                                    p.getDetail()
                            );
                        }
                );
    }

    private static CtFaultBean faultBeanWithCode(String faultCode) {
        CtFaultBean fault = new CtFaultBean();
        fault.setFaultCode(faultCode);
        return fault;
    }
}
