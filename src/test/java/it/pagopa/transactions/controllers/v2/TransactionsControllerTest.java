package it.pagopa.transactions.controllers.v2;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveExclusiveLockDocumentWrapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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

    @MockitoBean
    @Qualifier(it.pagopa.transactions.services.v1.TransactionsService.QUALIFIER_NAME)
    private it.pagopa.transactions.services.v1.TransactionsService transactionsServiceV1;

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
    private ReactiveExclusiveLockDocumentWrapper exclusiveLockDocumentWrapper;

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
    void shouldReturn422ForNoLockAcquiredOnPatchAuthRequest() {
        /* preconditions */
        TransactionId transactionId = new TransactionId(
                it.pagopa.ecommerce.commons.v2.TransactionTestUtils.TRANSACTION_ID
        );
        it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto updateAuthorizationRequestDto = new it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto()
                                .authorizationCode("authorizationCode")
                                .operationResult(
                                        it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED
                                )
                ).timestampOperation(java.time.OffsetDateTime.now());

        Mockito.when(exclusiveLockDocumentWrapper.saveIfAbsent(any())).thenReturn(Mono.just(false));
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

        Mockito.verify(exclusiveLockDocumentWrapper, never()).deleteById(any());
    }

    @Test
    void shouldReleaseLockOncePatchAuthRequestHaveBeenProcessed() {
        /* preconditions */
        String transactionId = it.pagopa.ecommerce.commons.v2.TransactionTestUtils.TRANSACTION_ID;
        UpdateAuthorizationRequestDto updateAuthorizationRequestDto = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeNpgGatewayDto()
                                .authorizationCode("authorizationCode")
                                .operationResult(
                                        OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED
                                )
                ).timestampOperation(java.time.OffsetDateTime.now());
        ExclusiveLockDocument expectedLockDocument = new ExclusiveLockDocument(
                "PATCH-auth-request-%s".formatted(transactionId),
                "transactions-service"
        );
        TransactionInfoDto transactionInfoDto = new TransactionInfoDto();
        transactionInfoDto.setTransactionId(transactionId);
        transactionInfoDto.setPayments(List.of());
        transactionInfoDto.setStatus(TransactionStatusDto.AUTHORIZATION_COMPLETED);

        Mockito.when(exclusiveLockDocumentWrapper.saveIfAbsent(any())).thenReturn(Mono.just(true));
        Mockito.when(exclusiveLockDocumentWrapper.deleteById(any())).thenReturn(Mono.just(true));
        Mockito.when(transactionsService.updateTransactionAuthorization(any(), any()))
                .thenReturn(Mono.just(transactionInfoDto));

        /* test */
        webTestClient.patch()
                .uri("/v2/transactions/{transactionId}/auth-requests", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", "primary-key")
                .bodyValue(updateAuthorizationRequestDto)
                .exchange()
                .expectStatus()
                .isOk();

        Mockito.verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(
                        arg -> {
                            assertEquals(expectedLockDocument.id(), arg.id());
                            assertEquals(expectedLockDocument.holderName(), arg.holderName());
                            return true;
                        }
                )
        );
        Mockito.verify(exclusiveLockDocumentWrapper, times(1)).deleteById(expectedLockDocument.id());
    }

    @Test
    void shouldReturnErrorWhenUpdateAuthorizationRequestFails() {
        /* preconditions */
        String transactionId = it.pagopa.ecommerce.commons.v2.TransactionTestUtils.TRANSACTION_ID;
        TransactionId transactionIdObj = new TransactionId(transactionId);
        it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto updateAuthorizationRequestDto = new it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto()
                                .authorizationCode("authorizationCode")
                                .operationResult(
                                        it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED
                                )
                ).timestampOperation(java.time.OffsetDateTime.now());

        Mockito.when(exclusiveLockDocumentWrapper.saveIfAbsent(any())).thenReturn(Mono.just(true));
        Mockito.when(transactionsService.updateTransactionAuthorization(eq(transactionIdObj.uuid()), any()))
                .thenReturn(Mono.error(new InvalidRequestException("Some error")));

        /* test */
        webTestClient.patch()
                .uri("/v2/transactions/{transactionId}/auth-requests", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", "primary-key")
                .bodyValue(updateAuthorizationRequestDto)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(transactionsService, times(1)).updateTransactionAuthorization(eq(transactionIdObj.uuid()), any());
    }

    private static CtFaultBean faultBeanWithCode(String faultCode) {
        CtFaultBean fault = new CtFaultBean();
        fault.setFaultCode(faultCode);
        return fault;
    }
}
