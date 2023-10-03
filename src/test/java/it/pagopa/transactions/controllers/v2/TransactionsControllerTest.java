package it.pagopa.transactions.controllers.v2;

import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.model.CtFaultBean;
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.services.v2.TransactionsService;
import it.pagopa.transactions.utils.JwtTokenUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

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

    @Mock
    ServerWebExchange mockExchange;

    @Mock
    ServerHttpRequest mockRequest;

    @Mock
    HttpHeaders mockHeaders;

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
            Mockito.when(jwtTokenUtils.generateToken(any(), any())).thenReturn(Mono.just(""));
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

            ResponseEntity<NewTransactionResponseDto> responseEntity = transactionsController
                    .newTransaction(clientIdDto, Mono.just(newTransactionRequestDto), null).block();

            // Verify mock
            Mockito.verify(transactionsService, Mockito.times(1))
                    .newTransaction(newTransactionRequestDto, clientIdDto, transactionId);

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
        Mockito.when(jwtTokenUtils.generateToken(any(), any())).thenReturn(Mono.just(""));
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

        ResponseEntity error = transactionsController.openStateHandler().block();

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
        Mockito.when(jwtTokenUtils.generateToken(any(), any())).thenReturn(Mono.just(""));
        Mockito.when(transactionsService.newTransaction(any(), any(), any()))
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
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void shouldReturnBadRequestForInvalidMail() {
        Mockito.when(jwtTokenUtils.generateToken(any(), any())).thenReturn(Mono.just(""));
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
                .uri("/v2/transactions")
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

    private static CtFaultBean faultBeanWithCode(String faultCode) {
        CtFaultBean fault = new CtFaultBean();
        fault.setFaultCode(faultCode);
        return fault;
    }
}
