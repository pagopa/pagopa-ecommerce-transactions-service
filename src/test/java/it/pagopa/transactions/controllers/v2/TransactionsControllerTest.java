package it.pagopa.transactions.controllers.v2;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.model.CtFaultBean;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

    @MockBean
    private it.pagopa.transactions.controllers.v1.TransactionsController transactionsControllerV1;

    @Mock
    ServerWebExchange mockExchange;

    @Mock
    ServerHttpRequest mockRequest;

    @Mock
    HttpHeaders mockHeaders;

    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
            Map.of("circuit-breaker-test", CircuitBreakerConfig.ofDefaults())
    );

    @Test
    void shouldGetOk() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);
        try (MockedStatic<UUID> uuidMockedStatic = Mockito.mockStatic(UUID.class)) {
            uuidMockedStatic.when(UUID::randomUUID).thenReturn(transactionId.uuid());
            String RPTID = "77777777777302016723749670035";
            String EMAIL = "mario.rossi@email.com";
            UUID userId = UUID.randomUUID();
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
            Mockito.lenient()
                    .when(
                            transactionsService
                                    .newTransaction(
                                            newTransactionRequestDto,
                                            clientIdDto,
                                            UUID.randomUUID(),
                                            transactionId,
                                            userId
                                    )
                    )
                    .thenReturn(Mono.just(response));

            Mockito.when(mockExchange.getRequest())
                    .thenReturn(mockRequest);

            Mockito.when(mockExchange.getRequest().getMethod())
                    .thenReturn(HttpMethod.POST);

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
            verify(transactionsService, Mockito.times(1))
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

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "foo@test.it",
                    "FoO@TeSt.iT",
                    "FOO@TEST.IT"
            }
    )
    void shouldHandleTransactionCreatedWithMailCaseInsensitive(String email) {

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
                                    "Required header 'x-correlation-id' is not present."
                            )
                    );
                });
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
                .bodyValue(expectedRequest)
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectBody(UpdateAuthorizationResponseDto.class)
                .value(r -> assertEquals(TransactionStatusDto.CLOSURE_REQUESTED, r.getStatus()));
        verify(transactionsControllerV1, times(1))
                .handleUpdateAuthorizationRequest(eq(transactionId), eq(expectedRequest), any());
    }

    private static CtFaultBean faultBeanWithCode(String faultCode) {
        CtFaultBean fault = new CtFaultBean();
        fault.setFaultCode(faultCode);
        return fault;
    }
}
