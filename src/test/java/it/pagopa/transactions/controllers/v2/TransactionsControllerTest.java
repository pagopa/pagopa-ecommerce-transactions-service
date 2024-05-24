package it.pagopa.transactions.controllers.v2;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.domain.Claims;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.exceptions.JWTTokenGenerationException;
import it.pagopa.ecommerce.commons.utils.JwtTokenUtils;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.model.CtFaultBean;
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.services.v2.TransactionsService;
import it.pagopa.transactions.utils.OpenTelemetryUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
@WebFluxTest(it.pagopa.transactions.controllers.v2.TransactionsController.class)
@TestPropertySource(locations = "classpath:application-tests.properties")
@AutoConfigureDataRedis
class TransactionsControllerTest {

    @InjectMocks
    private it.pagopa.transactions.controllers.v2.TransactionsController transactionsController = new it.pagopa.transactions.controllers.v2.TransactionsController();

    @MockBean
    @Qualifier(TransactionsService.QUALIFIER_NAME)
    private TransactionsService transactionsService;

    @MockBean
    private JwtTokenUtils jwtTokenUtils;

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private TransactionsUtils transactionsUtils;

    @MockBean
    private UUIDUtils uuidUtils;

    @MockBean
    private UniqueIdUtils uniqueIdUtils;

    @MockBean
    private OpenTelemetryUtils openTelemetryUtils;

    @Mock
    ServerWebExchange mockExchange;

    @Mock
    ServerHttpRequest mockRequest;

    @Mock
    HttpHeaders mockHeaders;

    private CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
            Map.of("circuit-breaker-test", CircuitBreakerConfig.ofDefaults())
    );

    @Test
    void shouldGetOk() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);
        try (MockedStatic<UUID> uuidMockedStatic = Mockito.mockStatic(UUID.class)) {
            uuidMockedStatic.when(UUID::randomUUID).thenReturn(transactionId.uuid());
            String RPTID = "77777777777302016723749670035";
            String EMAIL = "mario.rossi@email.com";
            ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
            NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto();
            newTransactionRequestDto.addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(RPTID));
            newTransactionRequestDto.setEmail(EMAIL);
            newTransactionRequestDto.orderId("orderId");
            NewTransactionResponseDto response = new NewTransactionResponseDto();
            PaymentInfoDto paymentInfoDto = new PaymentInfoDto();
            paymentInfoDto.setAmount(10);
            paymentInfoDto.setReason("Reason");
            paymentInfoDto.setPaymentToken("payment_token");
            paymentInfoDto.setRptId(RPTID);
            response.addPaymentsItem(paymentInfoDto);
            response.setAuthToken("token");
            Mockito.when(
                    jwtTokenUtils.generateToken(
                            any(SecretKey.class),
                            anyInt(),
                            eq(new Claims(transactionId, "orderId", null))
                    )
            ).thenReturn(Either.right(""));
            Mockito.lenient()
                    .when(
                            transactionsService
                                    .newTransaction(
                                            newTransactionRequestDto,
                                            clientIdDto,
                                            UUID.randomUUID(),
                                            transactionId,
                                            UUID.randomUUID()
                                    )
                    )
                    .thenReturn(Mono.just(response));

            Mockito.when(mockExchange.getRequest())
                    .thenReturn(mockRequest);

            Mockito.when(mockExchange.getRequest().getMethodValue())
                    .thenReturn("POST");

            Mockito.when(mockExchange.getRequest().getURI())
                    .thenReturn(
                            URI.create(
                                    String.join(
                                            "/",
                                            "https://localhost/transactions",
                                            transactionId.value()
                                    )
                            )
                    );

            ResponseEntity<NewTransactionResponseDto> responseEntity = transactionsController
                    .newTransaction(
                            clientIdDto,
                            UUID.randomUUID(),
                            Mono.just(newTransactionRequestDto),
                            UUID.randomUUID(),
                            mockExchange
                    )
                    .block();

            // Verify mock
            Mockito.verify(transactionsService, Mockito.times(1))
                    .newTransaction(
                            newTransactionRequestDto,
                            clientIdDto,
                            UUID.randomUUID(),
                            transactionId,
                            UUID.randomUUID()
                    );

            // Verify status code and response
            assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
            assertEquals(response, responseEntity.getBody());
        }
    }

    @Test
    void testAlreadyProcessedTransactionExceptionHandler() {
        final TransactionId transactionId = new TransactionId(UUID.randomUUID());

        ResponseEntity responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Transaction already processed")
                        .detail("Transaction for RPT id '' has been already processed"),
                HttpStatus.CONFLICT
        );
        AlreadyProcessedException exception = new AlreadyProcessedException(transactionId);

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
    void shouldReturnProblemJsonWith400OnBadInput() {
        Mockito.when(jwtTokenUtils.generateToken(any(SecretKey.class), anyInt(), any(Claims.class)))
                .thenReturn(Either.right(""));
        webTestClient.post()
                .uri("/v2/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .body(BodyInserters.fromValue("{}"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemJsonDto.class)
                .value(p -> assertEquals(400, p.getStatus()));
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

    @ParameterizedTest
    @EnumSource(PartyConfigurationFaultDto.class)
    void shouldReturnResponseEntityWithPartyConfigurationFault(PartyConfigurationFaultDto nodoErrorCode) {
        String rptId = "77777777777302000100000009424";
        CtFaultBean faultBean = faultBeanWithCode(nodoErrorCode.getValue());
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("email@test.it")
                .orderId("orderId")
                .idCart(TransactionTestUtils.ID_CART);

        Mockito.when(transactionsService.newTransaction(any(), any(), any(), any(), any()))
                .thenThrow(new NodoErrorException(faultBean));
        webTestClient.post()
                .uri("/v2/transactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("x-correlation-id", UUID.randomUUID().toString())
                .exchange()

                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody(PartyConfigurationFaultPaymentProblemJsonDto.class)
                .value(response -> {
                    assertEquals(
                            PartyConfigurationFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.DOMAIN_UNKNOWN,
                            response.getFaultCodeCategory()
                    );
                    assertEquals(nodoErrorCode.getValue(), response.getFaultCodeDetail().getValue());
                });
    }

    @ParameterizedTest
    @EnumSource(ValidationFaultPaymentUnknownDto.class)
    void shouldReturnResponseEntityWithValidationFaultPaymentUnknown(
                                                                     ValidationFaultPaymentUnknownDto nodoErrorCode
    ) {
        String rptId = "77777777777302000100000009424";
        CtFaultBean faultBean = faultBeanWithCode(nodoErrorCode.getValue());
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("email@test.it")
                .orderId("orderId")
                .idCart(TransactionTestUtils.ID_CART);

        Mockito.when(transactionsService.newTransaction(any(), any(), any(), any(), any()))
                .thenThrow(new NodoErrorException(faultBean));
        webTestClient.post()
                .uri("/v2/transactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("x-correlation-id", UUID.randomUUID().toString())
                .exchange()

                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
                .expectBody(ValidationFaultPaymentUnknownProblemJsonDto.class)
                .value(response -> {
                    assertEquals(
                            ValidationFaultPaymentUnknownProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_UNKNOWN,
                            response.getFaultCodeCategory()
                    );
                    assertEquals(nodoErrorCode.getValue(), response.getFaultCodeDetail().getValue());
                });
    }

    @ParameterizedTest
    @EnumSource(ValidationFaultPaymentDataErrorDto.class)
    void shouldReturnResponseEntityWithValidationFaultPaymentDataError(
                                                                       ValidationFaultPaymentDataErrorDto nodoErrorCode
    ) {
        String rptId = "77777777777302000100000009424";
        CtFaultBean faultBean = faultBeanWithCode(nodoErrorCode.getValue());
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("email@test.it")
                .orderId("orderId")
                .idCart(TransactionTestUtils.ID_CART);

        Mockito.when(transactionsService.newTransaction(any(), any(), any(), any(), any()))
                .thenThrow(new NodoErrorException(faultBean));
        webTestClient.post()
                .uri("/v2/transactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("x-correlation-id", UUID.randomUUID().toString())
                .exchange()

                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
                .expectBody(ValidationFaultPaymentDataErrorProblemJsonDto.class)
                .value(response -> {
                    assertEquals(
                            ValidationFaultPaymentDataErrorProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_DATA_ERROR,
                            response.getFaultCodeCategory()
                    );
                    assertEquals(nodoErrorCode.getValue(), response.getFaultCodeDetail().getValue());
                });
    }

    @ParameterizedTest
    @EnumSource(ValidationFaultPaymentUnavailableDto.class)
    void shouldReturnResponseEntityWithValidationFaultPaymentUnavailable(
                                                                         ValidationFaultPaymentUnavailableDto nodoErrorCode
    ) {
        String rptId = "77777777777302000100000009424";
        CtFaultBean faultBean = faultBeanWithCode(nodoErrorCode.getValue());
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("email@test.it")
                .orderId("orderId")
                .idCart(TransactionTestUtils.ID_CART);

        Mockito.when(transactionsService.newTransaction(any(), any(), any(), any(), any()))
                .thenThrow(new NodoErrorException(faultBean));
        webTestClient.post()
                .uri("/v2/transactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("x-correlation-id", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody(ValidationFaultPaymentUnavailableProblemJsonDto.class)
                .value(response -> {
                    assertEquals(
                            ValidationFaultPaymentUnavailableProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_UNAVAILABLE,
                            response.getFaultCodeCategory()
                    );
                    assertEquals(nodoErrorCode.getValue(), response.getFaultCodeDetail().getValue());
                });
    }

    @ParameterizedTest
    @EnumSource(PaymentOngoingStatusFaultDto.class)
    void shouldReturnResponseEntityWithPaymentOngoingStatusFault(PaymentOngoingStatusFaultDto nodoErrorCode) {
        String rptId = "77777777777302000100000009424";
        CtFaultBean faultBean = faultBeanWithCode(nodoErrorCode.getValue());
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("email@test.it")
                .orderId("orderId")
                .idCart(TransactionTestUtils.ID_CART);

        Mockito.when(transactionsService.newTransaction(any(), any(), any(), any(), any()))
                .thenThrow(new NodoErrorException(faultBean));
        webTestClient.post()
                .uri("/v2/transactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("x-correlation-id", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(PaymentOngoingStatusFaultPaymentProblemJsonDto.class)
                .value(response -> {
                    assertEquals(
                            PaymentOngoingStatusFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_ONGOING,
                            response.getFaultCodeCategory()
                    );
                    assertEquals(nodoErrorCode.getValue(), response.getFaultCodeDetail().getValue());
                });
    }

    @ParameterizedTest
    @EnumSource(PaymentExpiredStatusFaultDto.class)
    void shouldReturnResponseEntityWithPaymentExpiredStatusFault(PaymentExpiredStatusFaultDto nodoErrorCode) {
        String rptId = "77777777777302000100000009424";
        CtFaultBean faultBean = faultBeanWithCode(nodoErrorCode.getValue());
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("email@test.it")
                .orderId("orderId")
                .idCart(TransactionTestUtils.ID_CART);

        Mockito.when(transactionsService.newTransaction(any(), any(), any(), any(), any()))
                .thenThrow(new NodoErrorException(faultBean));
        webTestClient.post()
                .uri("/v2/transactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("x-correlation-id", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(PaymentExpiredStatusFaultPaymentProblemJsonDto.class)
                .value(response -> {
                    assertEquals(
                            PaymentExpiredStatusFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_EXPIRED,
                            response.getFaultCodeCategory()
                    );
                    assertEquals(nodoErrorCode.getValue(), response.getFaultCodeDetail().getValue());
                });
    }

    @ParameterizedTest
    @EnumSource(PaymentCanceledStatusFaultDto.class)
    void shouldReturnResponseEntityWithPaymentCanceledStatusFault(PaymentCanceledStatusFaultDto nodoErrorCode) {
        String rptId = "77777777777302000100000009424";
        CtFaultBean faultBean = faultBeanWithCode(nodoErrorCode.getValue());
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("email@test.it")
                .orderId("orderId")
                .idCart(TransactionTestUtils.ID_CART);

        Mockito.when(transactionsService.newTransaction(any(), any(), any(), any(), any()))
                .thenThrow(new NodoErrorException(faultBean));
        webTestClient.post()
                .uri("/v2/transactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("x-correlation-id", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(PaymentCanceledStatusFaultPaymentProblemJsonDto.class)
                .value(response -> {
                    assertEquals(
                            PaymentCanceledStatusFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_CANCELED,
                            response.getFaultCodeCategory()
                    );
                    assertEquals(nodoErrorCode.getValue(), response.getFaultCodeDetail().getValue());
                });
    }

    @ParameterizedTest
    @EnumSource(PaymentDuplicatedStatusFaultDto.class)
    void shouldReturnResponseEntityWithPaymentDuplicatedStatusFault(
                                                                    PaymentDuplicatedStatusFaultDto nodoErrorCode
    ) {
        String rptId = "77777777777302000100000009424";
        CtFaultBean faultBean = faultBeanWithCode(nodoErrorCode.getValue());
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("email@test.it")
                .orderId("orderId")
                .idCart(TransactionTestUtils.ID_CART);

        Mockito.when(transactionsService.newTransaction(any(), any(), any(), any(), any()))
                .thenThrow(new NodoErrorException(faultBean));
        webTestClient.post()
                .uri("/v2/transactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("x-correlation-id", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(PaymentDuplicatedStatusFaultPaymentProblemJsonDto.class)
                .value(response -> {
                    assertEquals(
                            PaymentDuplicatedStatusFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_DUPLICATED,
                            response.getFaultCodeCategory()
                    );
                    assertEquals(nodoErrorCode.getValue(), response.getFaultCodeDetail().getValue());
                });
    }

    @Test
    void shouldReturnResponseEntityWithGenericFault() {
        String rptId = "77777777777302000100000009424";
        CtFaultBean faultBean = faultBeanWithCode("UNKNOWN_ERROR");
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("email@test.it")
                .orderId("orderId")
                .idCart(TransactionTestUtils.ID_CART);

        Mockito.when(transactionsService.newTransaction(any(), any(), any(), any(), any()))
                .thenThrow(new NodoErrorException(faultBean));
        webTestClient.post()
                .uri("/v2/transactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("x-correlation-id", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody(GatewayFaultPaymentProblemJsonDto.class)
                .value(response -> {
                    assertEquals(
                            GatewayFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.GENERIC_ERROR,
                            response.getFaultCodeCategory()
                    );
                    assertEquals("UNKNOWN_ERROR", response.getFaultCodeDetail());
                });
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
                .jwtTokenGenerationError(new JWTTokenGenerationException());
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

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "foo@test.it",
                    "FoO@TeSt.iT",
                    "FOO@TEST.IT"
            }
    )
    void shouldHandleTransactionCreatedWithMailCaseInsensitive(String email) {
        Mockito.when(jwtTokenUtils.generateToken(any(SecretKey.class), anyInt(), any(Claims.class)))
                .thenReturn(Either.right(""));
        Mockito.when(transactionsService.newTransaction(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new NewTransactionResponseDto()));
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email(email)
                .orderId("orderId")
                .idCart(TransactionTestUtils.ID_CART);
        webTestClient.post()
                .uri("/v2/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("X-Client-Id", "CHECKOUT")
                .header("x-correlation-id", UUID.randomUUID().toString())
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void shouldReturnBadRequestForInvalidMail() {
        Mockito.when(jwtTokenUtils.generateToken(any(SecretKey.class), anyInt(), any(Claims.class)))
                .thenReturn(Either.right(""));
        Mockito.when(transactionsService.newTransaction(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new NewTransactionResponseDto()));
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("invalidMail")
                .idCart(TransactionTestUtils.ID_CART);
        webTestClient.post()
                .uri("/v2/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("X-Client-Id", "CHECKOUT")
                .header("x-correlation-id", UUID.randomUUID().toString())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemJsonDto.class)
                .value(
                        p -> {
                            assertEquals(400, p.getStatus());
                            assertTrue(
                                    p.getDetail().contains(
                                            "Field error in object 'newTransactionRequestDtoMono' on field 'email'"
                                    )
                            );
                        }
                );
    }

    @Test
    void shouldReturnProblemJsonWith400OnMissingCorrelationId() {
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("invalidMail")
                .idCart(TransactionTestUtils.ID_CART);
        webTestClient.post()
                .uri("/v2/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .bodyValue(newTransactionRequestDto)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemJsonDto.class)
                .value(p -> {
                    assertEquals(400, p.getStatus());
                    assertTrue(
                            p.getDetail().contains(
                                    "Missing request header 'x-correlation-id' for method parameter of type UUID"
                            )
                    );
                });
    }

    private static CtFaultBean faultBeanWithCode(String faultCode) {
        CtFaultBean fault = new CtFaultBean();
        fault.setFaultCode(faultCode);
        return fault;
    }
}
