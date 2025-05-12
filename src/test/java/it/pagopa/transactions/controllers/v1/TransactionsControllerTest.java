package it.pagopa.transactions.controllers.v1;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.v2.Claims;
import it.pagopa.ecommerce.commons.domain.v2.PaymentToken;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.exceptions.JWTTokenGenerationException;
import it.pagopa.ecommerce.commons.redis.templatewrappers.ExclusiveLockDocumentWrapper;
import it.pagopa.ecommerce.commons.utils.v2.JwtTokenUtils;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.model.CtFaultBean;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.services.v1.TransactionsService;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
import org.springframework.http.*;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@WebFluxTest(TransactionsController.class)
@TestPropertySource(locations = "classpath:application-tests.properties")
@AutoConfigureDataRedis
class TransactionsControllerTest {

    @InjectMocks
    private TransactionsController transactionsController = new TransactionsController();

    @MockBean
    @Qualifier(TransactionsService.QUALIFIER_NAME)
    private TransactionsService transactionsService;

    @MockBean
    @Qualifier("jwtTokenUtilsV2")
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
    private UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils;

    @MockBean
    private OpenTelemetryUtils openTelemetryUtils;

    private CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
            Map.of("circuit-breaker-test", CircuitBreakerConfig.ofDefaults())
    );

    @Mock
    ServerWebExchange mockExchange;

    @Mock
    ServerHttpRequest mockRequest;

    @Mock
    HttpHeaders mockHeaders;

    @MockBean
    private ExclusiveLockDocumentWrapper exclusiveLockDocumentWrapper;
    private final Integer paymentTokenValidityTime = 120;

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
                            eq(new Claims(transactionId, "orderId", null, null))
                    )
            ).thenReturn(Either.right(""));
            Mockito.lenient()
                    .when(
                            transactionsService
                                    .newTransaction(
                                            newTransactionRequestDto,
                                            clientIdDto,
                                            transactionId
                                    )
                    )
                    .thenReturn(Mono.just(response));

            Mockito.when(mockExchange.getRequest())
                    .thenReturn(mockRequest);

            Mockito.when(mockExchange.getRequest().getMethodValue())
                    .thenReturn("POST");

            Mockito.when(mockExchange.getRequest().getURI())
                    .thenReturn(URI.create("https://localhost/transactions"));

            ResponseEntity<NewTransactionResponseDto> responseEntity = transactionsController
                    .newTransaction(clientIdDto, Mono.just(newTransactionRequestDto), mockExchange).block();

            // Verify mock
            verify(transactionsService, Mockito.times(1))
                    .newTransaction(newTransactionRequestDto, clientIdDto, transactionId);

            // Verify status code and response
            assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
            assertEquals(response, responseEntity.getBody());
        }
    }

    @Test
    void shouldGetTransactionInfoGetPaymentToken() {

        TransactionInfoDto response = new TransactionInfoDto();
        PaymentInfoDto paymentInfoDto = new PaymentInfoDto();
        paymentInfoDto.setAmount(10);
        paymentInfoDto.setReason("Reason");
        paymentInfoDto.setPaymentToken("payment_token");
        response.addPaymentsItem(paymentInfoDto);
        response.setAuthToken("token");

        String transactionId = new TransactionId(UUID.randomUUID()).value();

        Mockito.lenient().when(transactionsService.getTransactionInfo(transactionId, null))
                .thenReturn(Mono.just(response));

        Mockito.when(mockExchange.getRequest())
                .thenReturn(mockRequest);

        Mockito.when(mockExchange.getRequest().getMethodValue())
                .thenReturn("GET");

        Mockito.when(mockExchange.getRequest().getURI())
                .thenReturn(URI.create(String.join("/", "https://localhost/transactions", transactionId)));

        ResponseEntity<TransactionInfoDto> responseEntity = transactionsController
                .getTransactionInfo(transactionId, null, mockExchange).block();

        // Verify mock
        verify(transactionsService, Mockito.times(1)).getTransactionInfo(transactionId, null);

        // Verify status code and response
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(response, responseEntity.getBody());
    }

    @Test
    void shouldCancelTransactionInfo() {

        String transactionId = new TransactionId(UUID.randomUUID()).value();
        Mockito.lenient().when(transactionsService.cancelTransaction(transactionId, null))
                .thenReturn(Mono.empty());

        Mockito.when(mockExchange.getRequest())
                .thenReturn(mockRequest);

        Mockito.when(mockExchange.getRequest().getMethodValue())
                .thenReturn("DELETE");

        Mockito.when(mockExchange.getRequest().getURI())
                .thenReturn(URI.create(String.join("/", "https://localhost/transactions", transactionId)));

        ResponseEntity<Void> responseEntity = transactionsController
                .requestTransactionUserCancellation(transactionId, null, mockExchange).block();

        // Verify mock
        verify(transactionsService, Mockito.times(1)).cancelTransaction(transactionId, null);

        // Verify status code and response
        assertEquals(HttpStatus.ACCEPTED, responseEntity.getStatusCode());
    }

    @Test
    void shouldReturnTransactionNotFoundForCancelTransactionInfo() {

        String transactionId = new TransactionId(UUID.randomUUID()).value();
        /* preconditions */
        Mockito.when(transactionsService.cancelTransaction(transactionId, null))
                .thenReturn(Mono.error(new TransactionNotFoundException(transactionId)));

        Mockito.when(mockExchange.getRequest())
                .thenReturn(mockRequest);

        Mockito.when(mockExchange.getRequest().getMethodValue())
                .thenReturn("DELETE");

        Mockito.when(mockExchange.getRequest().getURI())
                .thenReturn(URI.create(String.join("/", "https://localhost/transactions", transactionId)));

        /* test */

        StepVerifier.create(
                transactionsController
                        .requestTransactionUserCancellation(transactionId, null, mockExchange)
        )
                .expectErrorMatches(TransactionNotFoundException.class::isInstance)
                .verify();
    }

    @Test
    void shouldRedirectToAuthorizationURIForValidRequest() throws URISyntaxException {
        String transactionId = new TransactionId(UUID.randomUUID()).value();
        String paymentMethodId = "paymentMethodId";
        String client = "CHECKOUT";
        String pgsId = "NPG";

        /* preconditions */
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(1)
                .paymentInstrumentId(paymentMethodId)
                .pspId("pspId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT)
                .isAllCCP(false)
                .details(
                        new CardsAuthRequestDetailsDto()
                                .orderId("orderId")
                                .detailType("cards")
                );
        RequestAuthorizationResponseDto authorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(new URI("https://example.com").toString());

        Mockito.when(
                transactionsService
                        .requestTransactionAuthorization(transactionId, null, pgsId, null, authorizationRequest)
        )
                .thenReturn(Mono.just(authorizationResponse));

        /* test */
        webTestClient.post()
                .uri("/transactions/{transactionId}/auth-requests", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(authorizationRequest)
                .header("X-Client-Id", client)
                .header("X-Pgs-Id", pgsId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(RequestAuthorizationResponseDto.class)
                .value(response -> assertEquals(authorizationResponse, response));

    }

    @Test
    void shouldReturnNotFoundForNonExistingRequest() throws URISyntaxException {
        String transactionId = new TransactionId(UUID.randomUUID()).value();
        String paymentMethodId = "paymentMethodId";
        String client = "CHECKOUT";
        String pgsId = "NPG";

        /* preconditions */
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(1)
                .paymentInstrumentId(paymentMethodId)
                .pspId("pspId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT)
                .isAllCCP(false)
                .details(
                        new CardsAuthRequestDetailsDto()
                                .orderId("orderId")
                                .detailType("cards")
                );
        RequestAuthorizationResponseDto authorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(new URI("https://example.com").toString());

        Mockito.when(
                transactionsService
                        .requestTransactionAuthorization(transactionId, null, pgsId, null, authorizationRequest)
        )
                .thenReturn(Mono.error(new TransactionNotFoundException(transactionId)));

        /* test */
        webTestClient.post()
                .uri("/transactions/{transactionId}/auth-requests", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(authorizationRequest)
                .header("X-Client-Id", client)
                .header("X-Pgs-Id", pgsId)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(ProblemJsonDto.class)
                .value(
                        p -> assertEquals(
                                new ProblemJsonDto()
                                        .title("Transaction not found")
                                        .status(404)
                                        .detail(
                                                "Transaction for payment token '%s' not found".formatted(transactionId)
                                        ),
                                p
                        )
                );

    }

    @Test
    void testTransactionNotFoundExceptionHandler() {
        final String PAYMENT_TOKEN = "aaa";

        ResponseEntity<ProblemJsonDto> responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(404)
                        .title("Transaction not found")
                        .detail("Transaction for payment token not found"),
                HttpStatus.NOT_FOUND
        );
        TransactionNotFoundException exception = new TransactionNotFoundException(PAYMENT_TOKEN);

        ResponseEntity<ProblemJsonDto> response = transactionsController.transactionNotFoundHandler(exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
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
    void testUnsatisfiablePspRequestExceptionHandler() {
        final PaymentToken PAYMENT_TOKEN = new PaymentToken("aaa");
        final RequestAuthorizationRequestDto.LanguageEnum language = RequestAuthorizationRequestDto.LanguageEnum.IT;
        final int requestedFee = 10;

        ResponseEntity responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Cannot find a PSP with the requested parameters")
                        .detail("Cannot find a PSP with fee and language for transaction with payment token ''"),
                HttpStatus.CONFLICT
        );
        UnsatisfiablePspRequestException exception = new UnsatisfiablePspRequestException(
                PAYMENT_TOKEN,
                language,
                requestedFee
        );

        ResponseEntity<ProblemJsonDto> response = transactionsController.unsatisfiablePspRequestHandler(exception);

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
    void shouldReturnTransactionInfoOnCorrectNotify() {
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = new TransactionId(UUID.randomUUID()).value();

        TransactionInfoDto transactionInfo = new TransactionInfoDto()
                .transactionId(transactionId)
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .amount(100)
                                .paymentToken(paymentToken)
                )
                .status(TransactionStatusDto.NOTIFIED_OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        AddUserReceiptResponseDto expected = new AddUserReceiptResponseDto()
                .outcome(AddUserReceiptResponseDto.OutcomeEnum.OK);

        /* preconditions */
        Mockito.when(transactionsService.addUserReceipt(transactionId, addUserReceiptRequest))
                .thenReturn(Mono.just(transactionInfo));

        Mockito.when(mockExchange.getRequest())
                .thenReturn(mockRequest);

        Mockito.when(mockExchange.getRequest().getMethodValue())
                .thenReturn("POST");

        Mockito.when(mockExchange.getRequest().getURI())
                .thenReturn(
                        URI.create(String.join("/", "https://localhost/transactions", transactionId, "user-receipts"))
                );

        /* test */
        ResponseEntity<AddUserReceiptResponseDto> response = transactionsController
                .addUserReceipt(transactionId, Mono.just(addUserReceiptRequest), mockExchange).block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
    }

    @Test
    void shouldReturnProblemJsonWith400OnBadInput() {
        Mockito.when(jwtTokenUtils.generateToken(any(SecretKey.class), anyInt(), any(Claims.class)))
                .thenReturn(Either.right(""));
        webTestClient.post()
                .uri("/transactions")
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

    @Test
    void shouldReturnResponseEntityWithPartyConfigurationFault() {
        CtFaultBean faultBean = faultBeanWithCode(PartyConfigurationFaultDto.PPT_DOMINIO_DISABILITATO.getValue());
        ResponseEntity<PartyConfigurationFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<PartyConfigurationFaultPaymentProblemJsonDto>) transactionsController
                .nodoErrorHandler(
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
        assertEquals(
                FaultCategoryDto.PAYMENT_UNAVAILABLE,
                responseEntity.getBody().getFaultCodeCategory()
        );
        assertEquals(
                PartyConfigurationFaultDto.PPT_DOMINIO_DISABILITATO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithValidationFault() {
        CtFaultBean faultBean = faultBeanWithCode(ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue());

        ResponseEntity<ValidationFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<ValidationFaultPaymentProblemJsonDto>) transactionsController
                .nodoErrorHandler(
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.NOT_FOUND, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.PAYMENT_UNKNOWN, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithGatewayFault() {
        CtFaultBean faultBean = faultBeanWithCode(GatewayFaultDto.PAA_SYSTEM_ERROR.getValue());

        ResponseEntity<GatewayFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<GatewayFaultPaymentProblemJsonDto>) transactionsController
                .nodoErrorHandler(
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.GENERIC_ERROR, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                GatewayFaultDto.PAA_SYSTEM_ERROR.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithPartyTimeoutFault() {
        CtFaultBean faultBean = faultBeanWithCode(PartyTimeoutFaultDto.PPT_STAZIONE_INT_PA_IRRAGGIUNGIBILE.getValue());
        ResponseEntity<PartyTimeoutFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<PartyTimeoutFaultPaymentProblemJsonDto>) transactionsController
                .nodoErrorHandler(
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.GENERIC_ERROR, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                PartyTimeoutFaultDto.PPT_STAZIONE_INT_PA_IRRAGGIUNGIBILE.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithPaymentStatusFault() {
        CtFaultBean faultBean = faultBeanWithCode(PaymentStatusFaultDto.PAA_PAGAMENTO_IN_CORSO.getValue());
        ResponseEntity<PaymentStatusFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<PaymentStatusFaultPaymentProblemJsonDto>) transactionsController
                .nodoErrorHandler(
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.CONFLICT, responseEntity.getStatusCode());
        assertEquals(
                FaultCategoryDto.PAYMENT_UNAVAILABLE,
                responseEntity.getBody().getFaultCodeCategory()
        );
        assertEquals(
                PaymentStatusFaultDto.PAA_PAGAMENTO_IN_CORSO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
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
        ServerWebExchange exchange = Mockito.mock(ServerWebExchange.class);
        ServerHttpRequest serverHttpRequest = Mockito.mock(ServerHttpRequest.class);
        RequestPath requestPath = Mockito.mock(RequestPath.class);
        given(exchange.getRequest()).willReturn(serverHttpRequest);
        given(serverHttpRequest.getPath()).willReturn(requestPath);
        given(requestPath.value()).willReturn("");
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .validationExceptionHandler(new InvalidRequestException("Some message"), exchange);
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

    @ParameterizedTest
    @MethodSource("badRequestForUpdateAuthRequestMethodSource")
    void shouldReturnResponseEntityWithMismatchAmount(
                                                      String paymentGatewayTypeHeaderValue,
                                                      UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger expectedTrigger
    ) {
        String contextPath = "test/auth-requests";
        ServerWebExchange exchange = Mockito.mock(ServerWebExchange.class);
        ServerHttpRequest serverHttpRequest = Mockito.mock(ServerHttpRequest.class);
        RequestPath requestPath = Mockito.mock(RequestPath.class);
        HttpHeaders httpHeaders = Mockito.mock(HttpHeaders.class);
        given(exchange.getRequest()).willReturn(serverHttpRequest);
        given(serverHttpRequest.getPath()).willReturn(requestPath);
        given(serverHttpRequest.getHeaders()).willReturn(httpHeaders);
        given(serverHttpRequest.getMethod()).willReturn(HttpMethod.POST);
        given(httpHeaders.get("x-payment-gateway-type")).willReturn(List.of());
        if (paymentGatewayTypeHeaderValue != null) {
            given(httpHeaders.get("x-pgs-id")).willReturn(List.of(paymentGatewayTypeHeaderValue));
        } else {
            given(httpHeaders.get("x-pgs-id")).willReturn(List.of());
        }
        given(requestPath.value()).willReturn(contextPath);
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .amountMismatchErrorHandler(
                        new TransactionAmountMismatchException(1, 2),
                        exchange
                );

        assertEquals(HttpStatus.CONFLICT, responseEntity.getStatusCode());
        assertEquals(
                "Invalid request: Transaction amount mismatch",
                responseEntity.getBody().getDetail()
        );
        verify(updateTransactionStatusTracerUtils, times(1)).traceStatusUpdateOperation(
                new UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType.AUTHORIZATION_REQUESTED,
                        expectedTrigger,
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST
                )
        );
    }

    @ParameterizedTest
    @MethodSource("badRequestForUpdateAuthRequestMethodSource")
    void shouldReturnResponseEntityWithMismatchAllCCP(
                                                      String paymentGatewayTypeHeaderValue,
                                                      UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger expectedTrigger
    ) {
        String contextPath = "test/auth-requests";
        ServerWebExchange exchange = Mockito.mock(ServerWebExchange.class);
        ServerHttpRequest serverHttpRequest = Mockito.mock(ServerHttpRequest.class);
        RequestPath requestPath = Mockito.mock(RequestPath.class);
        HttpHeaders httpHeaders = Mockito.mock(HttpHeaders.class);
        given(exchange.getRequest()).willReturn(serverHttpRequest);
        given(serverHttpRequest.getPath()).willReturn(requestPath);
        given(serverHttpRequest.getHeaders()).willReturn(httpHeaders);
        given(serverHttpRequest.getMethod()).willReturn(HttpMethod.POST);
        given(httpHeaders.get("x-payment-gateway-type")).willReturn(List.of());
        if (paymentGatewayTypeHeaderValue != null) {
            given(httpHeaders.get("x-pgs-id")).willReturn(List.of(paymentGatewayTypeHeaderValue));
        } else {
            given(httpHeaders.get("x-pgs-id")).willReturn(List.of());
        }
        given(requestPath.value()).willReturn(contextPath);
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .paymentNoticeAllCCPMismatchErrorHandler(
                        new PaymentNoticeAllCCPMismatchException("testRptID", false, true),
                        exchange

                );

        assertEquals(HttpStatus.CONFLICT, responseEntity.getStatusCode());
        assertEquals(
                "Invalid request: Payment notice allCCP mismatch",
                responseEntity.getBody().getDetail()
        );
        verify(updateTransactionStatusTracerUtils, times(1)).traceStatusUpdateOperation(
                new UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType.AUTHORIZATION_REQUESTED,
                        expectedTrigger,
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST
                )
        );
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

    @Test
    void shouldGetTransactionInfoInAllStatuses() {

        TransactionInfoDto response = new TransactionInfoDto()
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .amount(10)
                                .reason("Reason")
                                .paymentToken("payment_token")
                ).authToken("token");

        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        Mockito.when(
                jwtTokenUtils.generateToken(
                        any(SecretKey.class),
                        anyInt(),
                        eq(new Claims(new TransactionId(transactionId), null, null, null))
                )
        ).thenReturn(Either.right(""));
        for (it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto status : it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
                .values()) {
            response.setStatus(TransactionStatusDto.fromValue(status.toString()));
            Mockito.when(transactionsService.getTransactionInfo(transactionId, null))
                    .thenReturn(Mono.just(response));
            webTestClient.get()
                    .uri("/transactions/{trnId}", Map.of("trnId", transactionId))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(TransactionInfoDto.class)
                    .value(p -> assertEquals(status.toString(), p.getStatus().toString()));
        }
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
        Mockito.when(transactionsService.newTransaction(any(), any(), any()))
                .thenReturn(Mono.just(new NewTransactionResponseDto()));
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email(email)
                .idCart(TransactionTestUtils.ID_CART);
        webTestClient.post()
                .uri("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("X-Client-Id", "CHECKOUT")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void shouldReturnBadRequestForInvalidMail() {
        Mockito.when(jwtTokenUtils.generateToken(any(SecretKey.class), anyInt(), any(Claims.class)))
                .thenReturn(Either.right(""));
        Mockito.when(transactionsService.newTransaction(any(), any(), any()))
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
                .uri("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("X-Client-Id", "CHECKOUT")
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
    void shouldReturnNotFoundForNonExistingPaymentMethodInAuthorizationRequest() {
        String transactionId = new TransactionId(UUID.randomUUID()).value();
        String paymentMethodId = "paymentMethodId";
        String client = "CHECKOUT";
        String pgsId = "NPG";

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(1)
                .paymentInstrumentId(paymentMethodId)
                .pspId("pspId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT)
                .isAllCCP(false)
                .details(
                        new CardsAuthRequestDetailsDto()
                                .orderId("orderId")
                                .detailType("cards")
                );

        /* preconditions */
        PaymentMethodNotFoundException exception = new PaymentMethodNotFoundException(paymentMethodId, client);

        Mockito.when(
                transactionsService
                        .requestTransactionAuthorization(transactionId, null, pgsId, null, authorizationRequest)
        )
                .thenReturn(Mono.error(exception));
        /* test */
        webTestClient.post()
                .uri("/transactions/{transactionId}/auth-requests", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(authorizationRequest)
                .header("X-Client-Id", client)
                .header("X-Pgs-Id", pgsId)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(ProblemJsonDto.class)
                .value(
                        p -> {
                            assertEquals(404, p.getStatus());
                            assertEquals(exception.getMessage(), p.getDetail());
                        }
                );
    }

    @Test
    void shouldReturnBadRequestForAuthorizationRequestPerformedWithPgsIdVPOS() {
        String transactionId = new TransactionId(UUID.randomUUID()).value();
        String paymentMethodId = "paymentMethodId";
        String client = "CHECKOUT";
        String pgsId = "VPOS";

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(1)
                .paymentInstrumentId(paymentMethodId)
                .pspId("pspId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT)
                .isAllCCP(false)
                .details(
                        new CardsAuthRequestDetailsDto()
                                .orderId("orderId")
                                .detailType("cards")
                );

        /* test */
        webTestClient.post()
                .uri("/transactions/{transactionId}/auth-requests", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(authorizationRequest)
                .header("X-Client-Id", client)
                .header("X-Pgs-Id", pgsId)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemJsonDto.class)
                .value(
                        p -> assertEquals(400, p.getStatus())
                );
        verify(exclusiveLockDocumentWrapper, times(0)).saveIfAbsent(any(), any());
    }

    @Test
    void shouldReturnBadRequestForAuthorizationRequestPerformedWithPgsIdXPAY() {
        String transactionId = new TransactionId(UUID.randomUUID()).value();
        String paymentMethodId = "paymentMethodId";
        String client = "CHECKOUT";
        String pgsId = "XPAY";

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(1)
                .paymentInstrumentId(paymentMethodId)
                .pspId("pspId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT)
                .isAllCCP(false)
                .details(
                        new CardsAuthRequestDetailsDto()
                                .orderId("orderId")
                                .detailType("cards")
                );

        /* test */
        webTestClient.post()
                .uri("/transactions/{transactionId}/auth-requests", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(authorizationRequest)
                .header("X-Client-Id", client)
                .header("X-Pgs-Id", pgsId)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemJsonDto.class)
                .value(
                        p -> assertEquals(400, p.getStatus())
                );
        verify(exclusiveLockDocumentWrapper, times(0)).saveIfAbsent(any(), any());
    }

    @Test
    void shouldReturnUnprocessableEntityForBadGatewayInSendPaymentResult() {
        Mockito.when(transactionsService.addUserReceipt(eq(TransactionTestUtils.TRANSACTION_ID), any()))
                .thenReturn(Mono.error(new BadGatewayException("Bad gateway", HttpStatus.BAD_GATEWAY)));

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        webTestClient.post()
                .uri(
                        builder -> builder
                                .pathSegment("transactions", TransactionTestUtils.TRANSACTION_ID, "user-receipts")
                                .build()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .body(BodyInserters.fromValue(addUserReceiptRequest))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody(ProblemJsonDto.class)
                .value(p -> assertEquals(422, p.getStatus()));

    }

    @Test
    void shouldReturnNotFoundInSendPaymentResultForNonExistingTransaction() {
        Mockito.when(transactionsService.addUserReceipt(eq(TransactionTestUtils.TRANSACTION_ID), any()))
                .thenReturn(Mono.error(new TransactionNotFoundException(UUID.randomUUID().toString())));

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        webTestClient.post()
                .uri(
                        builder -> builder
                                .pathSegment("transactions", TransactionTestUtils.TRANSACTION_ID, "user-receipts")
                                .build()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .body(BodyInserters.fromValue(addUserReceiptRequest))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND)
                .expectBody(ProblemJsonDto.class)
                .value(p -> assertEquals(404, p.getStatus()));
    }

    @Test
    void shouldReturnUnprocessableEntityInSendPaymentResultForTransactionAlreadyProcessed() {
        Mockito.when(transactionsService.addUserReceipt(eq(TransactionTestUtils.TRANSACTION_ID), any()))
                .thenReturn(
                        Mono.error(
                                new AlreadyProcessedException(new TransactionId(TransactionTestUtils.TRANSACTION_ID))
                        )
                );

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        webTestClient.post()
                .uri(
                        builder -> builder
                                .pathSegment("transactions", TransactionTestUtils.TRANSACTION_ID, "user-receipts")
                                .build()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .body(BodyInserters.fromValue(addUserReceiptRequest))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody(ProblemJsonDto.class)
                .value(p -> assertEquals(422, p.getStatus()));
    }

    @Test
    void shouldReturnUnprocessableEntityInSendPaymentResultForUncaughtError() {
        Mockito.when(transactionsService.addUserReceipt(eq(TransactionTestUtils.TRANSACTION_ID), any()))
                .thenReturn(Mono.error(new RuntimeException("Spooky!")));

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        webTestClient.post()
                .uri(
                        builder -> builder
                                .pathSegment("transactions", TransactionTestUtils.TRANSACTION_ID, "user-receipts")
                                .build()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .body(BodyInserters.fromValue(addUserReceiptRequest))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody(ProblemJsonDto.class)
                .value(p -> assertEquals(422, p.getStatus()));
    }

    private static Stream<Arguments> badRequestForUpdateAuthRequestMethodSource() {
        return Stream.of(
                Arguments.of("NPG", UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NPG),
                Arguments.of("REDIRECT", UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.REDIRECT),
                Arguments.of(null, UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.UNKNOWN),
                Arguments.of(
                        "unmanaged payment gateway",
                        UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.UNKNOWN
                )
        );
    }

    @ParameterizedTest
    @MethodSource("badRequestForUpdateAuthRequestMethodSource")
    void shouldTraceSyntacticInvalidRequestForUpdateAuthRequest(
                                                                String paymentGatewayTypeHeaderValue,
                                                                UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger expectedTrigger
    ) {
        String contextPath = "auth-requests";

        ServerWebExchange exchange = Mockito.mock(ServerWebExchange.class);
        ServerHttpRequest serverHttpRequest = Mockito.mock(ServerHttpRequest.class);
        RequestPath requestPath = Mockito.mock(RequestPath.class);
        HttpHeaders httpHeaders = Mockito.mock(HttpHeaders.class);
        given(exchange.getRequest()).willReturn(serverHttpRequest);
        given(serverHttpRequest.getPath()).willReturn(requestPath);
        given(serverHttpRequest.getHeaders()).willReturn(httpHeaders);
        given(serverHttpRequest.getMethod()).willReturn(HttpMethod.PATCH);
        if (paymentGatewayTypeHeaderValue != null) {
            given(httpHeaders.get("x-payment-gateway-type")).willReturn(List.of(paymentGatewayTypeHeaderValue));
        } else {
            given(httpHeaders.get("x-payment-gateway-type")).willReturn(List.of());
        }
        given(requestPath.value()).willReturn(contextPath);

        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .validationExceptionHandler(new InvalidRequestException("Some message"), exchange);
        assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        assertEquals("Invalid request: Some message", responseEntity.getBody().getDetail());

        verify(updateTransactionStatusTracerUtils, times(1)).traceStatusUpdateOperation(
                new UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType.AUTHORIZATION_OUTCOME,
                        expectedTrigger,
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST
                )
        );
    }

    @Test
    void shouldTraceSyntacticInvalidRequestForSendPaymentResult() {
        String contextPath = "user-receipts";
        UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate expectedStatusUpdateInfo = new UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate(
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType.SEND_PAYMENT_RESULT_OUTCOME,
                UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NODO,
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST
        );
        ServerWebExchange exchange = Mockito.mock(ServerWebExchange.class);
        ServerHttpRequest serverHttpRequest = Mockito.mock(ServerHttpRequest.class);
        RequestPath requestPath = Mockito.mock(RequestPath.class);
        given(exchange.getRequest()).willReturn(serverHttpRequest);
        given(serverHttpRequest.getPath()).willReturn(requestPath);
        given(requestPath.value()).willReturn(contextPath);

        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .validationExceptionHandler(new InvalidRequestException("Some message"), exchange);
        assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        assertEquals("Invalid request: Some message", responseEntity.getBody().getDetail());
        verify(updateTransactionStatusTracerUtils, times(1)).traceStatusUpdateOperation(
                expectedStatusUpdateInfo
        );
    }

    @Test
    void shouldNotTraceInvalidRequestExceptionForUnmanagedPaths() {
        ServerWebExchange exchange = Mockito.mock(ServerWebExchange.class);
        ServerHttpRequest serverHttpRequest = Mockito.mock(ServerHttpRequest.class);
        RequestPath requestPath = Mockito.mock(RequestPath.class);
        given(exchange.getRequest()).willReturn(serverHttpRequest);
        given(serverHttpRequest.getPath()).willReturn(requestPath);
        given(requestPath.value()).willReturn("unmanagedPath");
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .validationExceptionHandler(new InvalidRequestException("Some message"), exchange);
        assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        assertEquals("Invalid request: Some message", responseEntity.getBody().getDetail());
        verify(updateTransactionStatusTracerUtils, times(0)).traceStatusUpdateOperation(any());
    }

    @Test
    void shouldHandleAddUserReceiptStatusOK() {

        AddUserReceiptRequestDto addUserReceiptRequestDto = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK).paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                                .paymentToken("paymentToken")
                                .description("description")
                );

        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        String paymentToken = "paymentToken";
        TransactionInfoDto transactionInfo = new TransactionInfoDto()
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .amount(100)
                                .paymentToken(paymentToken)
                )
                .authToken("authToken")
                .status(TransactionStatusDto.AUTHORIZATION_COMPLETED);

        AddUserReceiptResponseDto addUserReceiptResponseDto = new AddUserReceiptResponseDto()
                .outcome(AddUserReceiptResponseDto.OutcomeEnum.OK);

        /* preconditions */
        Mockito.when(
                transactionsService.addUserReceipt(transactionId.value(), addUserReceiptRequestDto)
        )
                .thenReturn(Mono.just(transactionInfo));
        Mockito.when(uuidUtils.uuidFromBase64(transactionId.value())).thenReturn(Either.right(transactionId.uuid()));
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
                                        transactionId.value(),
                                        "user-receipts"
                                )
                        )
                );
        Hooks.onOperatorDebug();
        /* test */

        StepVerifier.create(
                transactionsController
                        .addUserReceipt(
                                transactionId.value(),
                                Mono.just(addUserReceiptRequestDto),
                                mockExchange
                        )
        )
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(addUserReceiptResponseDto, response.getBody());
                })
                .verifyComplete();

        verify(updateTransactionStatusTracerUtils, times(0))
                .traceStatusUpdateOperation(any());
    }

    private static Stream<Arguments> sendPaymentResultKO_ErrorStatusTransactionUpdate() {
        return Stream.of(
                Arguments.of(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.PROCESSING_ERROR,
                        new RuntimeException("Error processing request")
                ),
                Arguments.of(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.TRANSACTION_NOT_FOUND,
                        new TransactionNotFoundException("paymentToken")
                ),
                Arguments.of(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.PROCESSING_ERROR,
                        new ProcessingErrorException("Error processing request")
                )
        );
    }

    @ParameterizedTest
    @MethodSource("sendPaymentResultKO_ErrorStatusTransactionUpdate")
    void shouldTraceAddUserReceiptStatusKOForProcessingError(
                                                             UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome expectedOutcome,
                                                             Throwable raisedException
    ) {
        AddUserReceiptRequestDto addUserReceiptRequestDto = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK).paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                                .paymentToken("paymentToken")
                                .description("description")
                );
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

        /* preconditions */
        Mockito.when(
                transactionsService.addUserReceipt(transactionId.value(), addUserReceiptRequestDto)
        )
                .thenReturn(Mono.error(raisedException));
        Mockito.when(uuidUtils.uuidFromBase64(transactionId.value())).thenReturn(Either.right(transactionId.uuid()));
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
                                        transactionId.value(),
                                        "user-receipts"
                                )
                        )
                );

        /* test */
        StepVerifier.create(
                transactionsController
                        .addUserReceipt(
                                transactionId.value(),
                                Mono.just(addUserReceiptRequestDto),
                                mockExchange
                        )
        )
                .expectErrorMatches(
                        exc -> exc instanceof SendPaymentResultException
                                && ((SendPaymentResultException) exc).cause.equals(raisedException)
                )
                .verify();

        UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate expectedStatusUpdateInfo = new UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate(
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType.SEND_PAYMENT_RESULT_OUTCOME,
                UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NODO,
                expectedOutcome
        );

        verify(updateTransactionStatusTracerUtils, times(1)).traceStatusUpdateOperation(
                expectedStatusUpdateInfo
        );
    }

    private static Stream<Arguments> sendPaymentResultKO_SendPaymentResultNodoStatusUpdate() {
        return Stream.of(
                Arguments.of(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.WRONG_TRANSACTION_STATUS,
                        new AlreadyProcessedException(
                                new TransactionId(TransactionTestUtils.TRANSACTION_ID),
                                TransactionTestUtils.PSP_ID,
                                TransactionTestUtils.PAYMENT_TYPE_CODE,
                                "CHECKOUT",
                                false,
                                new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult("OK", Optional.empty())
                        )
                ),
                Arguments.of(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST,
                        new InvalidRequestException(
                                "Invalid Request Exception",
                                new TransactionId(TransactionTestUtils.TRANSACTION_ID),
                                TransactionTestUtils.PSP_ID,
                                TransactionTestUtils.PAYMENT_TYPE_CODE,
                                "CHECKOUT",
                                false,
                                new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult("OK", Optional.empty())
                        )
                )
        );
    }

    @ParameterizedTest
    @MethodSource("sendPaymentResultKO_SendPaymentResultNodoStatusUpdate")
    void shouldTraceAddUserReceiptStatusKOForWrongTransactionStatus(
                                                                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome expectedOutcome,
                                                                    TransactionContext raisedException
    ) {
        AddUserReceiptRequestDto addUserReceiptRequestDto = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK).paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                                .paymentToken("paymentToken")
                                .description("description")
                );
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

        /* preconditions */
        Mockito.when(
                transactionsService.addUserReceipt(transactionId.value(), addUserReceiptRequestDto)
        )
                .thenReturn(Mono.error((Throwable) raisedException));
        Mockito.when(uuidUtils.uuidFromBase64(transactionId.value())).thenReturn(Either.right(transactionId.uuid()));
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
                                        transactionId.value(),
                                        "user-receipts"
                                )
                        )
                );

        /* test */
        StepVerifier.create(
                transactionsController
                        .addUserReceipt(
                                transactionId.value(),
                                Mono.just(addUserReceiptRequestDto),
                                mockExchange
                        )
        )
                .expectErrorMatches(
                        exc -> exc instanceof SendPaymentResultException
                                && ((SendPaymentResultException) exc).cause.equals(raisedException)
                )
                .verify();

        UpdateTransactionStatusTracerUtils.SendPaymentResultNodoStatusUpdate expectedStatusUpdateInfo = new UpdateTransactionStatusTracerUtils.SendPaymentResultNodoStatusUpdate(
                expectedOutcome,
                raisedException.pspId().get(),
                raisedException.paymentTypeCode().get(),
                Transaction.ClientId.valueOf(raisedException.clientId().get()),
                raisedException.walletPayment().get(),
                raisedException.gatewayOutcomeResult().get()
        );

        verify(updateTransactionStatusTracerUtils, times(1)).traceStatusUpdateOperation(
                expectedStatusUpdateInfo
        );
    }

    @Test
    void shouldReturn422UnprocessableEntityForNpgNotRetryableErrorExceptionOnAuthorization() {
        String transactionId = new TransactionId(UUID.randomUUID()).value();
        String paymentMethodId = "paymentMethodId";
        String client = "CHECKOUT";
        String pgsId = "NPG";

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(1)
                .paymentInstrumentId(paymentMethodId)
                .pspId("pspId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT)
                .isAllCCP(false)
                .details(
                        new CardsAuthRequestDetailsDto()
                                .orderId("orderId")
                                .detailType("cards")
                );
        HttpStatus gatewayHttpErrorCode = HttpStatus.INTERNAL_SERVER_ERROR;
        /* preconditions */
        NpgNotRetryableErrorException exception = new NpgNotRetryableErrorException(
                "Error description",
                gatewayHttpErrorCode
        );

        Mockito.when(
                transactionsService
                        .requestTransactionAuthorization(transactionId, null, pgsId, null, authorizationRequest)
        )
                .thenReturn(Mono.error(exception));

        /* test */
        webTestClient.post()
                .uri("/transactions/{transactionId}/auth-requests", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(authorizationRequest)
                .header("X-Client-Id", client)
                .header("X-Pgs-Id", pgsId)
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectBody(ProblemJsonDto.class)
                .value(
                        p -> {
                            assertEquals(422, p.getStatus());
                            assertEquals(exception.getDetail(), p.getDetail());
                        }
                );
    }

    @Test
    void shouldReturn422ForNoLockAcquiredOnPatchAuthRequest() {
        /* preconditions */
        String b64TransactionId = "aaa";
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        UpdateAuthorizationRequestDto updateAuthorizationRequestDto = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeNpgGatewayDto()
                                .authorizationCode("authorizationCode")
                                .operationResult(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)
                ).timestampOperation(OffsetDateTime.now());

        Mockito.when(uuidUtils.uuidFromBase64(b64TransactionId)).thenReturn(Either.right(transactionId.uuid()));
        /* test */
        webTestClient.patch()
                .uri("/transactions/{transactionId}/auth-requests", b64TransactionId)
                .contentType(MediaType.APPLICATION_JSON)
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

    @Test
    void shouldGetTransactionOutcomeInfoWithInfoEmptyOK() {
        TransactionOutcomeInfoDto response = new TransactionOutcomeInfoDto()
                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0).isFinalStatus(true);

        String transactionId = new TransactionId(UUID.randomUUID()).value();

        Mockito.lenient().when(transactionsService.getTransactionOutcome(eq(transactionId), any()))
                .thenReturn(Mono.just(response));

        Mockito.when(mockExchange.getRequest())
                .thenReturn(mockRequest);

        Mockito.when(mockExchange.getRequest().getMethodValue())
                .thenReturn("GET");

        Mockito.when(mockExchange.getRequest().getURI())
                .thenReturn(URI.create(String.join("/", "https://localhost/transactions", transactionId, "outcomes")));

        ResponseEntity<TransactionOutcomeInfoDto> responseEntity = transactionsController
                .getTransactionOutcomes(transactionId, null, mockExchange).block();

        // Verify mock
        verify(transactionsService, Mockito.times(1)).getTransactionOutcome(transactionId, null);

        // Verify status code and response
        Assertions.assertNotNull(responseEntity);
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(response, responseEntity.getBody());
    }

    private static CtFaultBean faultBeanWithCode(String faultCode) {
        CtFaultBean fault = new CtFaultBean();
        fault.setFaultCode(faultCode);
        return fault;
    }
}
