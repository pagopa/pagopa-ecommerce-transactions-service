package it.pagopa.transactions.controllers;

import it.pagopa.generated.nodoperpsp.model.FaultBean;
import it.pagopa.generated.payment.requests.model.*;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.generated.transactions.server.model.ProblemJsonDto;
import it.pagopa.transactions.domain.PaymentToken;
import it.pagopa.transactions.domain.RptId;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.services.TransactionsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
@WebFluxTest(TransactionsController.class)
class TransactionsControllerTest {

    @InjectMocks
    private TransactionsController transactionsController = new TransactionsController();

    @MockBean
    private TransactionsService transactionsService;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldGetOk() {
        String RPTID = "77777777777302016723749670035";
        String EMAIL = "mario.rossi@email.com";

        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto();
        newTransactionRequestDto.setRptId(RPTID);
        newTransactionRequestDto.setEmail(EMAIL);

        NewTransactionResponseDto response = new NewTransactionResponseDto();
        response.setAmount(10);
        response.setAuthToken("token");
        response.setReason("Reason");
        response.setPaymentToken("payment_token");
        response.setRptId(RPTID);

        Mockito.lenient().when(transactionsService.newTransaction(newTransactionRequestDto))
                .thenReturn(Mono.just(response));

        ResponseEntity<NewTransactionResponseDto> responseEntity = transactionsController
                .newTransaction(Mono.just(newTransactionRequestDto), null).block();

        // Verify mock
        Mockito.verify(transactionsService, Mockito.times(1)).newTransaction(newTransactionRequestDto);

        // Verify status code and response
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(response, responseEntity.getBody());
    }

    @Test
    void shouldGetTransactionInfoGetPaymentToken() {

        TransactionInfoDto response = new TransactionInfoDto();
        response.setAmount(10);
        response.setAuthToken("token");
        response.setReason("Reason");
        response.setPaymentToken("payment_token");

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

        /* preconditions */
        Mockito.when(transactionsService.requestTransactionAuthorization(paymentToken, authorizationRequest))
                .thenReturn(Mono.just(authorizationResponse));

        /* test */
        ResponseEntity<RequestAuthorizationResponseDto> response = transactionsController.requestTransactionAuthorization(paymentToken, Mono.just(authorizationRequest), null).block();

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

        /* preconditions */
        Mockito.when(transactionsService.requestTransactionAuthorization(paymentToken, authorizationRequest))
                .thenReturn(Mono.error(new TransactionNotFoundException(paymentToken)));

        /* test */
        Mono<ResponseEntity<RequestAuthorizationResponseDto>> mono = transactionsController.requestTransactionAuthorization(paymentToken, Mono.just(authorizationRequest), null);
        assertThrows(
                TransactionNotFoundException.class,
                () -> mono.block()
        );
    }

    @Test
    void shouldReturnTransactionInfoOnCorrectAuthorizationAndClosure() {
        String paymentToken = "paymentToken";

        TransactionInfoDto transactionInfo = new TransactionInfoDto()
                .amount(100)
                .authToken("authToken")
                .status(TransactionStatusDto.AUTHORIZED)
                .paymentToken(paymentToken);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        /* preconditions */
        Mockito.when(transactionsService.updateTransactionAuthorization(paymentToken, updateAuthorizationRequest))
                .thenReturn(Mono.just(transactionInfo));

        /* test */
        ResponseEntity<TransactionInfoDto> response = transactionsController.updateTransactionAuthorization(paymentToken, Mono.just(updateAuthorizationRequest), null).block();

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
        StepVerifier.create(transactionsController.updateTransactionAuthorization(paymentToken, Mono.just(updateAuthorizationRequest), null))
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

        StepVerifier.create(transactionsController.updateTransactionAuthorization(paymentToken, Mono.just(updateAuthorizationRequest), null))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();
    }

    @Test
    void testTransactionNotFoundExceptionHandler() throws NoSuchMethodException, SecurityException,
            IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        final String PAYMENT_TOKEN = "aaa";

        ResponseEntity responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(404)
                        .title("Transaction not found")
                        .detail("Transaction for payment token not found"),
                HttpStatus.NOT_FOUND);
        TransactionNotFoundException exception = new TransactionNotFoundException(PAYMENT_TOKEN);
        Method method = TransactionsController.class.getDeclaredMethod("transactionNotFoundHandler",
                TransactionNotFoundException.class);
        method.setAccessible(true);
        ResponseEntity response = (ResponseEntity) method.invoke(transactionsController, exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void testAlreadyProcessedTransactionExceptionHandler() throws NoSuchMethodException, SecurityException,
            IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        final RptId RPT_ID = new RptId("77777777777111111111111111111");

        ResponseEntity responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Transaction already processed")
                        .detail("Transaction for RPT id '' has been already processed"),
                HttpStatus.CONFLICT);
        AlreadyProcessedException exception = new AlreadyProcessedException(RPT_ID);
        Method method = TransactionsController.class.getDeclaredMethod("alreadyProcessedHandler", AlreadyProcessedException.class);
        method.setAccessible(true);
        ResponseEntity response = (ResponseEntity) method.invoke(transactionsController, exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void testUnsatisfiablePspRequestExceptionHandler() throws NoSuchMethodException, SecurityException,
            IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        final PaymentToken PAYMENT_TOKEN = new PaymentToken("aaa");
        final RequestAuthorizationRequestDto.LanguageEnum language = RequestAuthorizationRequestDto.LanguageEnum.IT;
        final int requestedFee = 10;

        ResponseEntity responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Cannot find a PSP with the requested parameters")
                        .detail("Cannot find a PSP with fee and language for transaction with payment token ''"),
                HttpStatus.CONFLICT);
        UnsatisfiablePspRequestException exception = new UnsatisfiablePspRequestException(PAYMENT_TOKEN, language, requestedFee);
        Method method = TransactionsController.class.getDeclaredMethod("unsatisfiablePspRequestHandler", UnsatisfiablePspRequestException.class);
        method.setAccessible(true);
        ResponseEntity response = (ResponseEntity) method.invoke(transactionsController, exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void testBadGatewayExceptionHandler() throws NoSuchMethodException, SecurityException,
            IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        ResponseEntity responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(502)
                        .title("Bad gateway")
                        .detail(null),
                HttpStatus.BAD_GATEWAY);
        BadGatewayException exception = new BadGatewayException("");
        Method method = TransactionsController.class.getDeclaredMethod("badGatewayHandler", BadGatewayException.class);
        method.setAccessible(true);
        ResponseEntity response = (ResponseEntity) method.invoke(transactionsController, exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void testGatewayTimeoutExceptionHandler() throws NoSuchMethodException, SecurityException,
            IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        ResponseEntity responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(504)
                        .title("Gateway timeout")
                        .detail(null),
                HttpStatus.GATEWAY_TIMEOUT);
        GatewayTimeoutException exception = new GatewayTimeoutException();
        Method method = TransactionsController.class.getDeclaredMethod("gatewayTimeoutHandler", GatewayTimeoutException.class);
        method.setAccessible(true);
        ResponseEntity response = (ResponseEntity) method.invoke(transactionsController, exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }


    @Test
    void shouldReturnActivationResultResponseDto() {
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();

        ActivationResultRequestDto activationResultRequestDto =
                new ActivationResultRequestDto()
                        .paymentToken(paymentToken);

        /* preconditions */

        ActivationResultResponseDto resultResponseDto = new ActivationResultResponseDto().outcome(ActivationResultResponseDto.OutcomeEnum.OK);

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
                .amount(100)
                .status(TransactionStatusDto.NOTIFIED)
                .paymentToken(paymentToken);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(new AddUserReceiptRequestPaymentsDto()
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
    void shouldReturnResponseEntityWithPartyConfigurationFault()  throws NoSuchMethodException,
            InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode(PartyConfigurationFaultDto.PPT_DOMINIO_DISABILITATO.getValue());
        Method method =
                TransactionsController.class.getDeclaredMethod(
                        "nodoErrorHandler", NodoErrorException.class);
        method.setAccessible(true);

        ResponseEntity<PartyConfigurationFaultPaymentProblemJsonDto> responseEntity =
                (ResponseEntity<PartyConfigurationFaultPaymentProblemJsonDto>)
                        method.invoke(
                                transactionsController,
                                new NodoErrorException(faultBean));

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
        assertEquals(
                FaultCategoryDto.PAYMENT_UNAVAILABLE, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                PartyConfigurationFaultDto.PPT_DOMINIO_DISABILITATO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue());
    }

    @Test
    void shouldReturnResponseEntityWithValidationFault()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode(ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue());

        Method method =
                TransactionsController.class.getDeclaredMethod(
                        "nodoErrorHandler", NodoErrorException.class);
        method.setAccessible(true);

        ResponseEntity<ValidationFaultPaymentProblemJsonDto> responseEntity =
                (ResponseEntity<ValidationFaultPaymentProblemJsonDto>)
                        method.invoke(
                                transactionsController,
                                new NodoErrorException(faultBean));

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.NOT_FOUND, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.PAYMENT_UNKNOWN, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue());
    }

    @Test
    void shouldReturnResponseEntityWithGatewayFault()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode(GatewayFaultDto.PAA_SYSTEM_ERROR.getValue());

        Method method =
                TransactionsController.class.getDeclaredMethod(
                        "nodoErrorHandler", NodoErrorException.class);
        method.setAccessible(true);

        ResponseEntity<GatewayFaultPaymentProblemJsonDto> responseEntity =
                (ResponseEntity<GatewayFaultPaymentProblemJsonDto>)
                        method.invoke(
                                transactionsController,
                                new NodoErrorException(faultBean));

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.GENERIC_ERROR, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                GatewayFaultDto.PAA_SYSTEM_ERROR.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue());
    }

    @Test
    void shouldReturnResponseEntityWithPartyTimeoutFault()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode(PartyTimeoutFaultDto.PPT_STAZIONE_INT_PA_IRRAGGIUNGIBILE.getValue());

        Method method =
                TransactionsController.class.getDeclaredMethod(
                        "nodoErrorHandler", NodoErrorException.class);
        method.setAccessible(true);

        ResponseEntity<PartyTimeoutFaultPaymentProblemJsonDto> responseEntity =
                (ResponseEntity<PartyTimeoutFaultPaymentProblemJsonDto>)
                        method.invoke(
                                transactionsController,
                                new NodoErrorException(faultBean));

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.GENERIC_ERROR, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                PartyTimeoutFaultDto.PPT_STAZIONE_INT_PA_IRRAGGIUNGIBILE.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue());
    }

    @Test
    void shouldReturnResponseEntityWithPaymentStatusFault()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode(PaymentStatusFaultDto.PAA_PAGAMENTO_IN_CORSO.getValue());

        Method method =
                TransactionsController.class.getDeclaredMethod(
                        "nodoErrorHandler", NodoErrorException.class);
        method.setAccessible(true);

        ResponseEntity<PaymentStatusFaultPaymentProblemJsonDto> responseEntity =
                (ResponseEntity<PaymentStatusFaultPaymentProblemJsonDto>)
                        method.invoke(
                                transactionsController,
                                new NodoErrorException(faultBean));

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.CONFLICT, responseEntity.getStatusCode());
        assertEquals(
                FaultCategoryDto.PAYMENT_UNAVAILABLE, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                PaymentStatusFaultDto.PAA_PAGAMENTO_IN_CORSO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue());
    }

    @Test
    void shouldReturnResponseEntityWithGenericGatewayFault()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode("UNKNOWN_ERROR");

        Method method =
                TransactionsController.class.getDeclaredMethod(
                        "nodoErrorHandler", NodoErrorException.class);
        method.setAccessible(true);

        ResponseEntity<ProblemJsonDto> responseEntity =
                (ResponseEntity<ProblemJsonDto>)
                        method.invoke(transactionsController, new NodoErrorException(faultBean));

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
    }

    private static FaultBean faultBeanWithCode(String faultCode) {
        FaultBean fault = new FaultBean();
        fault.setFaultCode(faultCode);

        return fault;
    }
}
