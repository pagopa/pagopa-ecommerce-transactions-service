package it.pagopa.transactions.controllers;

import it.pagopa.generated.transactions.server.model.*;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class TransactionsControllerTest {

    @InjectMocks
    private TransactionsController transactionsController = new TransactionsController();

    @Mock
    private TransactionsService transactionsService;

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
        assertThrows(
                TransactionNotFoundException.class,
                () -> transactionsController.requestTransactionAuthorization(paymentToken, Mono.just(authorizationRequest), null).block()
        );
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
        final RptId RPT_ID = new RptId("aaa");

        ResponseEntity responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Transaction already authorized")
                        .detail("Transaction for RPT id '' has been already authorized"),
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
        BadGatewayException exception = new BadGatewayException();
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
}
