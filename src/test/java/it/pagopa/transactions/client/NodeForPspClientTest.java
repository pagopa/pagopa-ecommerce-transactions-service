package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentRequestDto;
import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.model.*;
import it.pagopa.transactions.utils.soap.SoapEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import reactor.core.publisher.Mono;

import javax.xml.bind.JAXBElement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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

    @Test
    void shouldReturnActivatePaymentResponseGivenValidPaymentNoticeTest() {

        ObjectFactory objectFactory = new ObjectFactory();
        BigDecimal amount = BigDecimal.valueOf(1200);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        String paymentToken = UUID.randomUUID().toString();

        ActivatePaymentNoticeReq request = objectFactory.createActivatePaymentNoticeReq();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        request.setAmount(amount);
        request.setQrCode(qrCode);

        ActivatePaymentNoticeRes activatePaymentRes = objectFactory.createActivatePaymentNoticeRes();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amount);

        /**
         * preconditions
         */
        when(nodoWebClient.post()).thenReturn((RequestBodyUriSpec) requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class), any(Object[].class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(SoapEnvelope.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(ActivatePaymentNoticeRes.class)).thenReturn(Mono.just(activatePaymentRes));

        /**
         * test
         */
        ActivatePaymentNoticeRes testResponse = client
                .activatePaymentNotice(objectFactory.createActivatePaymentNoticeReq(request)).block();

        /**
         * asserts
         */
        assertThat(testResponse.getPaymentToken()).isEqualTo(paymentToken);
        assertThat(testResponse.getFiscalCodePA()).isEqualTo(fiscalCode);
        assertThat(testResponse.getTotalAmount()).isEqualTo(amount);
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

        ActivatePaymentNoticeReq request = objectFactory.createActivatePaymentNoticeReq();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        request.setAmount(amount);
        request.setQrCode(qrCode);
        JAXBElement<ActivatePaymentNoticeReq> jaxbElementRequest = objectFactory
                .createActivatePaymentNoticeReq(request);

        ActivatePaymentNoticeRes activatePaymentRes = objectFactory.createActivatePaymentNoticeRes();
        CtFaultBean fault = objectFactory.createCtFaultBean();
        fault.setFaultCode(faultError);
        fault.setFaultString(faultError);
        activatePaymentRes.setFault(fault);

        /**
         * preconditions
         */
        when(nodoWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(),any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class), any(Object[].class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(SoapEnvelope.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);

        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(ActivatePaymentNoticeRes.class)).thenReturn(Mono.just(activatePaymentRes));

        /**
         * test
         */
        ActivatePaymentNoticeRes testResponse = client
                .activatePaymentNotice(objectFactory.createActivatePaymentNoticeReq(request)).block();

        /**
         * asserts
         */
        assertThat(testResponse.getFault().getFaultCode()).isEqualTo(faultError);
        assertThat(testResponse.getFault().getFaultString()).isEqualTo(faultError);
    }

    @Test
    void shouldReturnOKClosePaymentResponse() {
        ClosePaymentRequestDto closePaymentRequest = new ClosePaymentRequestDto()
                .paymentTokens(List.of("paymentToken"))
                .outcome(ClosePaymentRequestDto.OutcomeEnum.OK)
                .identificativoPsp("identificativoPsp")
                .tipoVersamento(ClosePaymentRequestDto.TipoVersamentoEnum.CP)
                .identificativoIntermediario("identificativoIntermediario")
                .identificativoCanale("identificativoCanale")
                .pspTransactionId("transactionId")
                .fee(new BigDecimal(1))
                .timestampOperation(OffsetDateTime.now())
                .totalAmount(new BigDecimal(101))
                .additionalPaymentInformations(null);

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .esito(ClosePaymentResponseDto.EsitoEnum.OK);

        /* preconditions */
        when(nodoWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), eq(MediaType.APPLICATION_JSON_VALUE))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class), any(Object[].class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(ClosePaymentRequestDto.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(ClosePaymentResponseDto.class)).thenReturn(Mono.just(closePaymentResponse));

        ClosePaymentResponseDto clientResponse = client.closePayment(closePaymentRequest).block();

        /* test */
        assertThat(clientResponse.getEsito()).isEqualTo(closePaymentResponse.getEsito());
    }

    @Test
    void shouldReturnKOClosePaymentResponse() {
        ClosePaymentRequestDto closePaymentRequest = new ClosePaymentRequestDto()
                .paymentTokens(List.of("paymentToken"))
                .outcome(ClosePaymentRequestDto.OutcomeEnum.OK)
                .identificativoPsp("identificativoPsp")
                .tipoVersamento(ClosePaymentRequestDto.TipoVersamentoEnum.CP)
                .identificativoIntermediario("identificativoIntermediario")
                .identificativoCanale("identificativoCanale")
                .pspTransactionId("transactionId")
                .fee(new BigDecimal(1))
                .timestampOperation(OffsetDateTime.now())
                .totalAmount(new BigDecimal(101))
                .additionalPaymentInformations(null);

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .esito(ClosePaymentResponseDto.EsitoEnum.KO);

        /* preconditions */
        when(nodoWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), eq(MediaType.APPLICATION_JSON_VALUE))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class), any(Object[].class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(ClosePaymentRequestDto.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(ClosePaymentResponseDto.class)).thenReturn(Mono.just(closePaymentResponse));

        ClosePaymentResponseDto clientResponse = client.closePayment(closePaymentRequest).block();

        /* test */
        assertThat(clientResponse.getEsito()).isEqualTo(closePaymentResponse.getEsito());
    }
}
