package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.nodo.v2.dto.AdditionalPaymentInformationsDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.model.*;
import it.pagopa.transactions.configurations.WebClientsConfig;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.utils.soap.SoapEnvelope;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.shaded.com.fasterxml.jackson.core.JsonProcessingException;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.xml.bind.JAXBElement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NodeForPspClientTest {

    @InjectMocks
    private NodeForPspClient client;

    @Mock
    private WebClient nodoWebClient;

    @Mock
    private RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RequestHeadersSpec requestHeadersSpec;

    @Mock
    private ResponseSpec responseSpec;

    private static MockWebServer mockWebServer;

    @BeforeAll
    public static void beforeTests() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start(9000);
        System.out.printf("Mock web server started on %s:%s%n", mockWebServer.getHostName(), mockWebServer.getPort());
    }

    @AfterAll
    public static void afterAll() throws Exception {
        mockWebServer.close();
    }

    @Test
    void shouldReturnActivatePaymentResponseGivenValidPaymentNoticeTest() {

        ObjectFactory objectFactory = new ObjectFactory();
        BigDecimal amount = BigDecimal.valueOf(1200);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        String paymentToken = UUID.randomUUID().toString();
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactory.createCtTransferListPSPV2();
        String transactionId = UUID.randomUUID().toString();
        ActivatePaymentNoticeV2Request request = objectFactory.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        request.setAmount(amount);
        request.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactory.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amount);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);

        /**
         * preconditions
         */
        when(nodoWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(), any(Object[].class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(SoapEnvelope.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(ActivatePaymentNoticeV2Response.class)).thenReturn(Mono.just(activatePaymentRes));

        /**
         * test
         */
        ActivatePaymentNoticeV2Response testResponse = client
                .activatePaymentNoticeV2(objectFactory.createActivatePaymentNoticeV2Request(request), transactionId)
                .block();

        /**
         * asserts
         */
        assertThat(testResponse.getPaymentToken()).isEqualTo(paymentToken);
        assertThat(testResponse.getFiscalCodePA()).isEqualTo(fiscalCode);
        assertThat(testResponse.getTotalAmount()).isEqualTo(amount);
        assertThat(testResponse.getTransferList()).isEqualTo(ctTransferListPSPV2);
    }

    @Test
    void shouldReturnFaultGivenDuplicatePaymentNoticeTest() {

        /**
         * preconditions
         */
        ObjectFactory objectFactory = new ObjectFactory();
        BigDecimal amount = BigDecimal.valueOf(1200);
        String fiscalCode = "77777777777";
        String paymentNotice = "30200010000000999";
        String faultError = "PAA_PAGAMENTO_DUPLICATO";
        String transactionId = UUID.randomUUID().toString();
        ActivatePaymentNoticeV2Request request = objectFactory.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        request.setAmount(amount);
        request.setQrCode(qrCode);
        JAXBElement<ActivatePaymentNoticeV2Request> jaxbElementRequest = objectFactory
                .createActivatePaymentNoticeV2Request(request);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactory.createActivatePaymentNoticeV2Response();
        CtFaultBean fault = objectFactory.createCtFaultBean();
        fault.setFaultCode(faultError);
        fault.setFaultString(faultError);
        activatePaymentRes.setFault(fault);

        /**
         * preconditions
         */
        when(nodoWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(), any(Object[].class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(SoapEnvelope.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);

        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(ActivatePaymentNoticeV2Response.class)).thenReturn(Mono.just(activatePaymentRes));

        /**
         * test
         */
        ActivatePaymentNoticeV2Response testResponse = client
                .activatePaymentNoticeV2(jaxbElementRequest, transactionId).block();

        /**
         * asserts
         */
        assertThat(testResponse.getFault().getFaultCode()).isEqualTo(faultError);
        assertThat(testResponse.getFault().getFaultString()).isEqualTo(faultError);
    }

    @Test
    void shouldReturnResponseStatusExceptionOnActivatev2() {

        ObjectFactory objectFactory = new ObjectFactory();
        BigDecimal amount = BigDecimal.valueOf(1200);
        String fiscalCode = "77777777777";
        String paymentNotice = "30200010000000999";
        String transactionId = UUID.randomUUID().toString();
        ActivatePaymentNoticeV2Request request = objectFactory.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        request.setAmount(amount);
        request.setQrCode(qrCode);
        JAXBElement<ActivatePaymentNoticeV2Request> jaxbElementRequest = objectFactory
                .createActivatePaymentNoticeV2Request(request);
        /**
         * preconditions
         */
        when(nodoWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(), any(Object[].class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(SoapEnvelope.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);

        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(ActivatePaymentNoticeV2Response.class))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR)));

        StepVerifier
                .create(client.activatePaymentNoticeV2(jaxbElementRequest, transactionId))
                .expectError(ResponseStatusException.class);
    }

    @Test
    void shouldReturnOKClosePaymentResponse() {
        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(List.of("paymentToken"))
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP("identificativoPsp")
                .idBrokerPSP("identificativoIntermediario")
                .idChannel("identificativoCanale")
                .transactionId("transactionId")
                .fee(new BigDecimal(1))
                .timestampOperation(OffsetDateTime.now())
                .totalAmount(new BigDecimal(101))
                .additionalPaymentInformations(null);

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.OK);

        /* preconditions */
        when(nodoWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), eq(MediaType.APPLICATION_JSON_VALUE))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(Function.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(ClosePaymentRequestV2Dto.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(ClosePaymentResponseDto.class)).thenReturn(Mono.just(closePaymentResponse));

        ClosePaymentResponseDto clientResponse = client.closePaymentV2(closePaymentRequest).block();

        /* test */
        assertThat(clientResponse.getOutcome()).isEqualTo(closePaymentResponse.getOutcome());
    }

    @Test
    void shouldReturnOKClosePaymentResponseAdditionalInfo() {
        AdditionalPaymentInformationsDto additionalPaymentInformationsDto = new AdditionalPaymentInformationsDto()
                .outcomePaymentGateway(AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.OK)
                .totalAmount(new BigDecimal((101)).toString())
                .fee(new BigDecimal(1).toString())
                .timestampOperation(
                        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(List.of("paymentToken"))
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP("identificativoPsp")
                .idBrokerPSP("identificativoIntermediario")
                .idChannel("identificativoCanale")
                .transactionId("transactionId")
                .fee(new BigDecimal(1))
                .timestampOperation(OffsetDateTime.now())
                .totalAmount(new BigDecimal(101))
                .additionalPaymentInformations(additionalPaymentInformationsDto);

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.OK);

        /* preconditions */
        when(nodoWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), eq(MediaType.APPLICATION_JSON_VALUE))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(Function.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(ClosePaymentRequestV2Dto.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(ClosePaymentResponseDto.class)).thenReturn(Mono.just(closePaymentResponse));

        ClosePaymentResponseDto clientResponse = client.closePaymentV2(closePaymentRequest).block();

        /* test */
        assertThat(clientResponse.getOutcome()).isEqualTo(closePaymentResponse.getOutcome());
    }

    @Test
    void shouldReturnKOClosePaymentResponse() {
        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(List.of("paymentToken"))
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP("identificativoPsp")
                .idBrokerPSP("identificativoIntermediario")
                .idChannel("identificativoCanale")
                .transactionId("transactionId")
                .fee(new BigDecimal(1))
                .timestampOperation(OffsetDateTime.now())
                .totalAmount(new BigDecimal(101))
                .additionalPaymentInformations(null);

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        when(nodoWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), eq(MediaType.APPLICATION_JSON_VALUE))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(Function.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(ClosePaymentRequestV2Dto.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(ClosePaymentResponseDto.class)).thenReturn(Mono.just(closePaymentResponse));

        ClosePaymentResponseDto clientResponse = client.closePaymentV2(closePaymentRequest).block();

        /* test */
        assertThat(clientResponse.getOutcome()).isEqualTo(closePaymentResponse.getOutcome());
    }

    @Test
    void shouldMapClosePaymentErrorToBadGatewayException() {
        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(List.of("paymentToken"))
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP("identificativoPsp")
                .idBrokerPSP("identificativoIntermediario")
                .idChannel("identificativoCanale")
                .transactionId("transactionId")
                .fee(new BigDecimal(1))
                .timestampOperation(OffsetDateTime.now())
                .totalAmount(new BigDecimal(101))
                .additionalPaymentInformations(null);

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        when(nodoWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), eq(MediaType.APPLICATION_JSON_VALUE))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(Function.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(ClosePaymentRequestV2Dto.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        ResponseStatusException exception = new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error"
        );
        when(responseSpec.bodyToMono(ClosePaymentResponseDto.class)).thenReturn(Mono.error(exception));
        /* test */
        StepVerifier.create(client.closePaymentV2(closePaymentRequest))
                .expectErrorMatches(
                        e -> e instanceof BadGatewayException badGatewayException
                                && badGatewayException.getHttpStatus().equals(exception.getStatus())
                                && badGatewayException.getDetail().equals(exception.getReason())
                )
                .verify();
    }

    @Test
    void shouldCallNodoClosePaymentOnConfiguredPath() throws JsonProcessingException {
        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(List.of("paymentToken"))
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP("identificativoPsp")
                .idBrokerPSP("identificativoIntermediario")
                .idChannel("identificativoCanale")
                .transactionId("transactionId")
                .fee(new BigDecimal(1))
                .timestampOperation(OffsetDateTime.now())
                .totalAmount(new BigDecimal(101))
                .additionalPaymentInformations(null);

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.OK);

        /* preconditions */
        String closePaymentResponseBody = new ObjectMapper().writeValueAsString(closePaymentResponse);
        String nodoPerPmUri = "/nodo-per-pm/v2";
        String ecommerceClientId = "ecomm";
        String expectedQueryPath = nodoPerPmUri.concat("/closepayment").concat("?clientId=").concat(ecommerceClientId);
        Dispatcher dispatcher = new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals(expectedQueryPath)) {

                    return new MockResponse()
                            .setResponseCode(200)
                            .setBody(closePaymentResponseBody)
                            .setHeader("Content-Type", "application/json");
                }
                return new MockResponse().setResponseCode(404);
            }
        };

        mockWebServer.setDispatcher(dispatcher);
        NodeForPspClient nodeForPspClient = new NodeForPspClient(
                new WebClientsConfig().nodoWebClient(
                        "http://localhost:9000",
                        10000,
                        10000
                ),
                "/",
                ecommerceClientId,
                nodoPerPmUri
        );
        StepVerifier
                .create(nodeForPspClient.closePaymentV2(closePaymentRequest))
                .expectNext(closePaymentResponse)
                .verifyComplete();
    }

    @Test
    void shouldHandleClosePaymentWithHttpErrorCodeResponse() {
        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(List.of("paymentToken"))
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP("identificativoPsp")
                .idBrokerPSP("identificativoIntermediario")
                .idChannel("identificativoCanale")
                .transactionId("transactionId")
                .fee(new BigDecimal(1))
                .timestampOperation(OffsetDateTime.now())
                .totalAmount(new BigDecimal(101))
                .additionalPaymentInformations(null);

        /* preconditions */
        String nodoPerPmUri = "/nodo-per-pm/v2";
        String ecommerceClientId = "ecomm";
        Dispatcher dispatcher = new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setResponseCode(500);
            }
        };

        mockWebServer.setDispatcher(dispatcher);
        NodeForPspClient nodeForPspClient = new NodeForPspClient(
                new WebClientsConfig().nodoWebClient(
                        "http://localhost:9000",
                        10000,
                        10000
                ),
                "/",
                ecommerceClientId,
                nodoPerPmUri
        );
        StepVerifier
                .create(nodeForPspClient.closePaymentV2(closePaymentRequest))
                .expectErrorMatches(ex -> ex instanceof BadGatewayException)
                .verify();
    }

    @Test
    void shouldHandleClosePaymentWith422AnNotReceiveRPT() {
        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(List.of("paymentToken"))
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP("identificativoPsp")
                .idBrokerPSP("identificativoIntermediario")
                .idChannel("identificativoCanale")
                .transactionId("transactionId")
                .fee(new BigDecimal(1))
                .timestampOperation(OffsetDateTime.now())
                .totalAmount(new BigDecimal(101))
                .additionalPaymentInformations(null);

        /* preconditions */
        String nodoPerPmUri = "/nodo-per-pm/v2";
        String ecommerceClientId = "ecomm";
        Dispatcher dispatcher = new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setResponseCode(422)
                        .setBody("{\"outcome\":\"KO\",\"description\":\"Node did not receive RPT yet\"}");
            }
        };

        mockWebServer.setDispatcher(dispatcher);
        NodeForPspClient nodeForPspClient = new NodeForPspClient(
                new WebClientsConfig().nodoWebClient(
                        "http://localhost:9000",
                        10000,
                        10000
                ),
                "/",
                ecommerceClientId,
                nodoPerPmUri
        );
        StepVerifier
                .create(nodeForPspClient.closePaymentV2(closePaymentRequest))
                .expectErrorMatches(ex -> ex instanceof BadGatewayException)
                .verify();
    }
}
