package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.nodo.v2.dto.AdditionalPaymentInformationsDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeV2Request;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeV2Response;
import it.pagopa.generated.transactions.model.CtFaultBean;
import it.pagopa.generated.transactions.model.CtQrCode;
import it.pagopa.generated.transactions.model.CtTransferListPSPV2;
import it.pagopa.generated.transactions.model.ObjectFactory;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.utils.soap.SoapEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import org.springframework.web.server.ResponseStatusException;
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

    private String nodoClosePaymentApiKey = "key";

    @Test
    void shouldReturnActivatePaymentResponseGivenValidPaymentNoticeTest() {

        ObjectFactory objectFactory = new ObjectFactory();
        BigDecimal amount = BigDecimal.valueOf(1200);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        String paymentToken = UUID.randomUUID().toString();
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactory.createCtTransferListPSPV2();

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
                .activatePaymentNoticeV2(objectFactory.createActivatePaymentNoticeV2Request(request)).block();

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
                .activatePaymentNoticeV2(jaxbElementRequest).block();

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
                .create(client.activatePaymentNoticeV2(jaxbElementRequest))
                .expectError(ResponseStatusException.class);
    }

    @Test
    void shouldReturnOKClosePaymentResponse() {
        ReflectionTestUtils.setField(client, "nodoClosePaymentApiKey", "key");

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
        when(requestBodyUriSpec.header(any(), eq(nodoClosePaymentApiKey))).thenReturn(requestBodyUriSpec);
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
        ReflectionTestUtils.setField(client, "nodoClosePaymentApiKey", "key");

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
        when(requestBodyUriSpec.header(any(), eq(nodoClosePaymentApiKey))).thenReturn(requestBodyUriSpec);

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
        ReflectionTestUtils.setField(client, "nodoClosePaymentApiKey", "key");

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
        when(requestBodyUriSpec.header(any(), eq(nodoClosePaymentApiKey))).thenReturn(requestBodyUriSpec);
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
        ReflectionTestUtils.setField(client, "nodoClosePaymentApiKey", "key");

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
        when(requestBodyUriSpec.header(any(), eq(nodoClosePaymentApiKey))).thenReturn(requestBodyUriSpec);
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
}
