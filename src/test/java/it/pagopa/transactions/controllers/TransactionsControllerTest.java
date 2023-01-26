package it.pagopa.transactions.controllers;

import it.pagopa.ecommerce.commons.domain.PaymentToken;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.generated.nodoperpsp.model.FaultBean;
import it.pagopa.generated.payment.requests.model.*;
import it.pagopa.generated.transactions.server.model.ProblemJsonDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.services.TransactionsService;
import it.pagopa.transactions.utils.JwtTokenUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
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
import reactor.test.StepVerifier;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
@WebFluxTest(TransactionsController.class)
@TestPropertySource(locations = "classpath:application-tests.properties")
@AutoConfigureDataRedis
class TransactionsControllerTest {

    @InjectMocks
    private TransactionsController transactionsController = new TransactionsController();

    @MockBean
    private TransactionsService transactionsService;

    @MockBean
    private JwtTokenUtils jwtTokenUtils;

    @Autowired
    private WebTestClient webTestClient;

    @Mock
    ServerWebExchange mockExchange;

    @Mock
    ServerHttpRequest mockRequest;

    @Mock
    HttpHeaders mockHeaders;

    @Test
    void shouldGetOk() {
        UUID transactionId = UUID.fromString("95b278e6-a3ae-4c7b-9ac9-788fec1ceede");
        try (MockedStatic<UUID> uuidMock = Mockito.mockStatic(UUID.class)) {
            uuidMock.when(UUID::randomUUID).thenReturn(transactionId);
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
            Mockito.when(jwtTokenUtils.generateToken(any())).thenReturn(Mono.just(""));
            Mockito.lenient()
                    .when(
                            transactionsService
                                    .newTransaction(
                                            newTransactionRequestDto,
                                            clientIdDto
                                    )
                    )
                    .thenReturn(Mono.just(response));

        ResponseEntity<NewTransactionResponseDto> responseEntity = transactionsController
                .newTransaction(clientIdDto, Mono.just(newTransactionRequestDto), null).block();

            // Verify mock
            Mockito.verify(transactionsService, Mockito.times(1))
                    .newTransaction(newTransactionRequestDto, clientIdDto);

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

        String paymentToken = UUID.randomUUID().toString();

        Mockito.lenient().when(transactionsService.getTransactionInfo(paymentToken)).thenReturn(Mono.just(response));

        ResponseEntity<TransactionInfoDto> responseEntity = transactionsController
                .getTransactionInfo(paymentToken, null).block();

        // Verify mock
        Mockito.verify(transactionsService, Mockito.times(1)).getTransactionInfo(paymentToken);

        // Verify status code and response
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(response, responseEntity.getBody());
    }

    @Test
    void shouldRedirectToAuthorizationURIForValidRequest() throws URISyntaxException {
        String paymentToken = "paymentToken";
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(1)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("pspId");

        RequestAuthorizationResponseDto authorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(new URI("https://example.com").toString());
        String pgsId = "XPAY";

        /* preconditions */
        Mockito.when(transactionsService.requestTransactionAuthorization(paymentToken, pgsId, authorizationRequest))
                .thenReturn(Mono.just(authorizationResponse));

        /* test */
        ResponseEntity<RequestAuthorizationResponseDto> response = transactionsController
                .requestTransactionAuthorization(paymentToken, Mono.just(authorizationRequest), pgsId, mockExchange)
                .block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(authorizationResponse, response.getBody());
    }

    @Test
    void shouldReturnNotFoundForNonExistingRequest() {
        String paymentToken = "paymentToken";
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(1)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("pspId");

        String pgsId = "XPAY";

        /* preconditions */
        Mockito.when(transactionsService.requestTransactionAuthorization(paymentToken, pgsId, authorizationRequest))
                .thenReturn(Mono.error(new TransactionNotFoundException(paymentToken)));

        /* test */
        Mono<ResponseEntity<RequestAuthorizationResponseDto>> mono = transactionsController
                .requestTransactionAuthorization(paymentToken, Mono.just(authorizationRequest), pgsId, mockExchange);
        assertThrows(
                TransactionNotFoundException.class,
                () -> mono.block()
        );
    }

    @Test
    void shouldReturnTransactionInfoOnCorrectAuthorizationAndClosure() {
        String paymentToken = "paymentToken";

        TransactionInfoDto transactionInfo = new TransactionInfoDto()
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .amount(100)
                                .paymentToken(paymentToken)
                )
                .authToken("authToken")
                .status(TransactionStatusDto.AUTHORIZED);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        /* preconditions */
        Mockito.when(transactionsService.updateTransactionAuthorization(paymentToken, updateAuthorizationRequest))
                .thenReturn(Mono.just(transactionInfo));

        /* test */
        ResponseEntity<TransactionInfoDto> response = transactionsController
                .updateTransactionAuthorization(paymentToken, Mono.just(updateAuthorizationRequest), null).block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(transactionInfo, response.getBody());
    }

    @Test
    void shouldReturnNotFoundForAuthorizingNonExistingRequest() {
        String paymentToken = "paymentToken";

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        /* preconditions */
        Mockito.when(transactionsService.updateTransactionAuthorization(paymentToken, updateAuthorizationRequest))
                .thenReturn(Mono.error(new TransactionNotFoundException(paymentToken)));

        /* test */
        StepVerifier.create(
                transactionsController
                        .updateTransactionAuthorization(paymentToken, Mono.just(updateAuthorizationRequest), null)
        )
                .expectErrorMatches(error -> error instanceof TransactionNotFoundException)
                .verify();
    }

    @Test
    void shouldReturnBadGatewayOnNodoHttpError() {
        String paymentToken = "paymentToken";

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        /* preconditions */
        Mockito.when(transactionsService.updateTransactionAuthorization(paymentToken, updateAuthorizationRequest))
                .thenReturn(Mono.error(new BadGatewayException("")));

        /* test */

        StepVerifier.create(
                transactionsController
                        .updateTransactionAuthorization(paymentToken, Mono.just(updateAuthorizationRequest), null)
        )
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();
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
        BadGatewayException exception = new BadGatewayException("");
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
    void shouldReturnActivationResultResponseDto() {
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();

        ActivationResultRequestDto activationResultRequestDto = new ActivationResultRequestDto()
                .paymentToken(paymentToken);

        /* preconditions */

        ActivationResultResponseDto resultResponseDto = new ActivationResultResponseDto()
                .outcome(ActivationResultResponseDto.OutcomeEnum.OK);

        Mockito.when(transactionsService.activateTransaction(transactionId, activationResultRequestDto))
                .thenReturn(Mono.just(resultResponseDto));

        /* test */
        ResponseEntity<ActivationResultResponseDto> response = transactionsController
                .transactionActivationResult(transactionId, Mono.just(activationResultRequestDto), null).block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(resultResponseDto, response.getBody());
    }

    @Test
    void shouldReturnTransactionInfoOnCorrectNotify() {
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();

        TransactionInfoDto transactionInfo = new TransactionInfoDto()
                .transactionId(transactionId)
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .amount(100)
                                .paymentToken(paymentToken)
                )
                .status(TransactionStatusDto.NOTIFIED);

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

        /* test */
        ResponseEntity<AddUserReceiptResponseDto> response = transactionsController
                .addUserReceipt(transactionId, Mono.just(addUserReceiptRequest), null).block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
    }

    @Test
    void shouldReturnProblemJsonWith400OnBadInput() {
        Mockito.when(jwtTokenUtils.generateToken(any())).thenReturn(Mono.just(""));
        webTestClient.post()
                .uri("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{}"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemJsonDto.class)
                .value(p -> assertEquals(400, p.getStatus()));
    }

    @Test
    void shouldReturnErrorCircuitBreakerOpen() {

        ResponseEntity error = transactionsController.openStateHandler().block();

        // Verify status code and response
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
    }

    @Test
    void shouldReturnResponseEntityWithPartyConfigurationFault() {
        FaultBean faultBean = faultBeanWithCode(PartyConfigurationFaultDto.PPT_DOMINIO_DISABILITATO.getValue());
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
        FaultBean faultBean = faultBeanWithCode(ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue());

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
        FaultBean faultBean = faultBeanWithCode(GatewayFaultDto.PAA_SYSTEM_ERROR.getValue());

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
        FaultBean faultBean = faultBeanWithCode(PartyTimeoutFaultDto.PPT_STAZIONE_INT_PA_IRRAGGIUNGIBILE.getValue());
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
        FaultBean faultBean = faultBeanWithCode(PaymentStatusFaultDto.PAA_PAGAMENTO_IN_CORSO.getValue());
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
        FaultBean faultBean = faultBeanWithCode("UNKNOWN_ERROR");
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
    void shouldReturnResponseEntityWithMismatchAmount() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .amountMismatchErrorHandler(new TransactionAmountMismatchException(1, 2));

        assertEquals(HttpStatus.CONFLICT, responseEntity.getStatusCode());
        assertEquals(
                "Invalid request: Transaction amount mismatch",
                responseEntity.getBody().getDetail()
        );
    }

    @Test
    void shouldReturnResponseEntityWithInternalServerErrorForErrorGeneratingJwtToken() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .jwtTokenGenerationError(new JWTTokenGenerationException());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, responseEntity.getStatusCode());
        assertEquals("Internal server error: cannot generate JWT token", responseEntity.getBody().getDetail());

    }

    private static FaultBean faultBeanWithCode(String faultCode) {
        FaultBean fault = new FaultBean();
        fault.setFaultCode(faultCode);

        return fault;
    }
}
