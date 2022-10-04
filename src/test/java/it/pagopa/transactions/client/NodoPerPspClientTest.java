package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentRequestDto;
import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentResponseDto;
import it.pagopa.generated.nodoperpsp.model.*;
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
class NodoPerPspClientTest {

    @InjectMocks
    private NodoPerPspClient client;

    @Mock
    private WebClient nodoWebClient;

    @Mock
    private RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RequestHeadersSpec requestHeadersSpec;

    @Mock
    private ResponseSpec responseSpec;

    @Test
    void shouldReturnPaymentInfoGivenValidPaymentNoticeTest() {

        ObjectFactory objectFactory = new ObjectFactory();
        String ccp = UUID.randomUUID().toString();
        String codificaInfrastrutturaPSP = "codificaInfrastrutturaPSP";
        String identificativoPSP = "identificativoPSP";
        String identificativoCanale = "identificativoCanale";
        String password = "password";
        String identificativoIntermediarioPSP = "identificativoIntermediarioPSP";
        String esito = "OK";
        BigDecimal importoSingoloVersamento = BigDecimal.valueOf(1200);
        String causaleVersamento = "causaleVersamento";

        String paymentToken = UUID.randomUUID().toString();

        NodoVerificaRPT request = objectFactory.createNodoVerificaRPT();
        request.setCodiceContestoPagamento(ccp);
        request.setCodificaInfrastrutturaPSP(codificaInfrastrutturaPSP);
        request.setIdentificativoPSP(identificativoPSP);
        request.setIdentificativoCanale(identificativoCanale);
        request.setPassword(password);
        request.setIdentificativoIntermediarioPSP(identificativoIntermediarioPSP);

        NodoVerificaRPTRisposta verificaRPTRisposta = objectFactory.createNodoVerificaRPTRisposta();
        EsitoNodoVerificaRPTRisposta esitoVerifica = objectFactory.createEsitoNodoVerificaRPTRisposta();
        esitoVerifica.setEsito(esito);
        NodoTipoDatiPagamentoPA datiPagamento = objectFactory.createNodoTipoDatiPagamentoPA();
        datiPagamento.setImportoSingoloVersamento(importoSingoloVersamento);
        datiPagamento.setCausaleVersamento(causaleVersamento);
        esitoVerifica.setDatiPagamentoPA(datiPagamento);
        verificaRPTRisposta.setNodoVerificaRPTRisposta(esitoVerifica);

        /**
         * preconditions
         */
        when(nodoWebClient.post()).thenReturn((RequestBodyUriSpec) requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(), any(Object[].class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(SoapEnvelope.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(NodoVerificaRPTRisposta.class)).thenReturn(Mono.just(verificaRPTRisposta));

        /**
         * test
         */
        NodoVerificaRPTRisposta testResponse = client.verificaRPT(objectFactory.createNodoVerificaRPT(request)).block();

        /**
         * asserts
         */
        assertThat(testResponse.getNodoVerificaRPTRisposta().getEsito()).isEqualTo(esito);
        assertThat(testResponse.getNodoVerificaRPTRisposta().getDatiPagamentoPA().getImportoSingoloVersamento()).isEqualTo(importoSingoloVersamento);
        assertThat(testResponse.getNodoVerificaRPTRisposta().getDatiPagamentoPA().getCausaleVersamento()).isEqualTo(causaleVersamento);
    }

    @Test
    void shouldReturnFaultGivenDuplicatePaymentNoticeTest() {

        ObjectFactory objectFactory = new ObjectFactory();
        String ccp = UUID.randomUUID().toString();
        String codificaInfrastrutturaPSP = "codificaInfrastrutturaPSP";
        String identificativoPSP = "identificativoPSP";
        String identificativoCanale = "identificativoCanale";
        String password = "password";
        String identificativoIntermediarioPSP = "identificativoIntermediarioPSP";
        String esito = "KO";
        String faultError = "PAA_PAGAMENTO_DUPLICATO";

        NodoVerificaRPT request = objectFactory.createNodoVerificaRPT();
        request.setCodiceContestoPagamento(ccp);
        request.setCodificaInfrastrutturaPSP(codificaInfrastrutturaPSP);
        request.setIdentificativoPSP(identificativoPSP);
        request.setIdentificativoCanale(identificativoCanale);
        request.setPassword(password);
        request.setIdentificativoIntermediarioPSP(identificativoIntermediarioPSP);

        NodoVerificaRPTRisposta verificaRPTRisposta = objectFactory.createNodoVerificaRPTRisposta();
        EsitoNodoVerificaRPTRisposta esitoVerifica = objectFactory.createEsitoNodoVerificaRPTRisposta();
        esitoVerifica.setEsito(esito);

        FaultBean faultBean = objectFactory.createFaultBean();
        faultBean.setFaultCode(faultError);
        faultBean.setFaultString(faultError);
        esitoVerifica.setFault(faultBean);
        verificaRPTRisposta.setNodoVerificaRPTRisposta(esitoVerifica);

        /**
         * preconditions
         */
        when(nodoWebClient.post()).thenReturn((RequestBodyUriSpec) requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(), any(Object[].class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(SoapEnvelope.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(NodoVerificaRPTRisposta.class)).thenReturn(Mono.just(verificaRPTRisposta));

        /**
         * test
         */
        NodoVerificaRPTRisposta testResponse = client.verificaRPT(objectFactory.createNodoVerificaRPT(request)).block();

        /**
         * asserts
         */
        assertThat(testResponse.getNodoVerificaRPTRisposta().getEsito()).isEqualTo(esito);
        assertThat(testResponse.getNodoVerificaRPTRisposta().getFault().getFaultCode()).isEqualTo(faultError);
        assertThat(testResponse.getNodoVerificaRPTRisposta().getFault().getFaultString()).isEqualTo(faultError);
    }

    @Test
    void shouldActivateGivenValidPaymentNoticeTest() {

        ObjectFactory objectFactory = new ObjectFactory();
        String ccp = UUID.randomUUID().toString();
        String codificaInfrastrutturaPSP = "codificaInfrastrutturaPSP";
        String identificativoPSP = "identificativoPSP";
        String identificativoCanale = "identificativoCanale";
        String password = "password";
        String identificativoIntermediarioPSP = "identificativoIntermediarioPSP";
        String esito = "OK";
        BigDecimal importoSingoloVersamento = BigDecimal.valueOf(1200);
        String causaleVersamento = "causaleVersamento";
        String beneficiario = "beneficiario";
        CtEnteBeneficiario enteBeneficiario = objectFactory.createCtEnteBeneficiario();
        enteBeneficiario.setDenominazioneBeneficiario("denominazione");
        enteBeneficiario.setCapBeneficiario("00100");
        enteBeneficiario.setCivicoBeneficiario("1");
        enteBeneficiario.setIndirizzoBeneficiario("Via Roma");
        enteBeneficiario.setLocalitaBeneficiario("Roma");
        enteBeneficiario.setNazioneBeneficiario("Italia");
        enteBeneficiario.setProvinciaBeneficiario("RO");
        enteBeneficiario.setCodiceUnitOperBeneficiario("CodiceUnitOper");
        enteBeneficiario.setDenomUnitOperBeneficiario("DenomUnitOper");
        CtIdentificativoUnivocoPersonaG idPersona = objectFactory.createCtIdentificativoUnivocoPersonaG();
        idPersona.setCodiceIdentificativoUnivoco("codice");
        enteBeneficiario.setIdentificativoUnivocoBeneficiario(idPersona);

        NodoAttivaRPT request = objectFactory.createNodoAttivaRPT();
        request.setCodiceContestoPagamento(ccp);
        request.setCodificaInfrastrutturaPSP(codificaInfrastrutturaPSP);
        request.setIdentificativoPSP(identificativoPSP);
        request.setIdentificativoCanale(identificativoCanale);
        request.setPassword(password);
        request.setIdentificativoIntermediarioPSP(identificativoIntermediarioPSP);

        NodoAttivaRPTRisposta attivaRPTRisposta = objectFactory.createNodoAttivaRPTRisposta();
        EsitoNodoAttivaRPTRisposta esitoAttiva = objectFactory.createEsitoNodoAttivaRPTRisposta();
        NodoTipoDatiPagamentoPA datiPagamentoPA = objectFactory.createNodoTipoDatiPagamentoPA();
        datiPagamentoPA.setCausaleVersamento(causaleVersamento);
        datiPagamentoPA.setImportoSingoloVersamento(importoSingoloVersamento);
        datiPagamentoPA.setEnteBeneficiario(enteBeneficiario);
        datiPagamentoPA.setBicAccredito("AAAAA-BB-CC-123");
        datiPagamentoPA.setIbanAccredito("IT43J0300203280591787195952");

        esitoAttiva.setDatiPagamentoPA(datiPagamentoPA);
        esitoAttiva.setEsito(esito);

        attivaRPTRisposta.setNodoAttivaRPTRisposta(esitoAttiva);


        /**
         * preconditions
         */

        when(nodoWebClient.post()).thenReturn((RequestBodyUriSpec) requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(), any(Object[].class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(SoapEnvelope.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(NodoAttivaRPTRisposta.class)).thenReturn(Mono.just(attivaRPTRisposta));

        /**
         * test
         */
        NodoAttivaRPTRisposta testResponse = client.attivaRPT(objectFactory.createNodoAttivaRPT(request)).block();

        /**
         * asserts
         */
        assertThat(testResponse.getNodoAttivaRPTRisposta().getEsito()).isEqualTo(esito);
        assertThat(testResponse.getNodoAttivaRPTRisposta().getDatiPagamentoPA().getImportoSingoloVersamento()).isEqualTo(importoSingoloVersamento);
        assertThat(testResponse.getNodoAttivaRPTRisposta().getDatiPagamentoPA().getCausaleVersamento()).isEqualTo(causaleVersamento);
    }

    @Test
    void shouldGetErrorGivenInvalidPaymentNoticeTest() {

        ObjectFactory objectFactory = new ObjectFactory();
        String ccp = UUID.randomUUID().toString();
        String codificaInfrastrutturaPSP = "codificaInfrastrutturaPSP";
        String identificativoPSP = "identificativoPSP";
        String identificativoCanale = "identificativoCanale";
        String password = "password";
        String identificativoIntermediarioPSP = "identificativoIntermediarioPSP";
        String esito = "KO";
        BigDecimal importoSingoloVersamento = BigDecimal.valueOf(1200);
        String causaleVersamento = "causaleVersamento";
        String beneficiario = "beneficiario";
        CtEnteBeneficiario enteBeneficiario = objectFactory.createCtEnteBeneficiario();
        enteBeneficiario.setDenominazioneBeneficiario("denominazione");
        enteBeneficiario.setCapBeneficiario("00100");
        enteBeneficiario.setCivicoBeneficiario("1");
        enteBeneficiario.setIndirizzoBeneficiario("Via Roma");
        enteBeneficiario.setLocalitaBeneficiario("Roma");
        enteBeneficiario.setNazioneBeneficiario("Italia");
        enteBeneficiario.setProvinciaBeneficiario("RO");
        enteBeneficiario.setCodiceUnitOperBeneficiario("CodiceUnitOper");
        enteBeneficiario.setDenomUnitOperBeneficiario("DenomUnitOper");
        CtIdentificativoUnivocoPersonaG idPersona = objectFactory.createCtIdentificativoUnivocoPersonaG();
        idPersona.setCodiceIdentificativoUnivoco("codice");
        enteBeneficiario.setIdentificativoUnivocoBeneficiario(idPersona);
        String faultError = "PAA_PAGAMENTO_DUPLICATO";

        NodoAttivaRPT request = objectFactory.createNodoAttivaRPT();
        request.setCodiceContestoPagamento(ccp);
        request.setCodificaInfrastrutturaPSP(codificaInfrastrutturaPSP);
        request.setIdentificativoPSP(identificativoPSP);
        request.setIdentificativoCanale(identificativoCanale);
        request.setPassword(password);
        request.setIdentificativoIntermediarioPSP(identificativoIntermediarioPSP);

        NodoAttivaRPTRisposta attivaRPTRisposta = objectFactory.createNodoAttivaRPTRisposta();
        EsitoNodoAttivaRPTRisposta esitoAttiva = objectFactory.createEsitoNodoAttivaRPTRisposta();
        NodoTipoDatiPagamentoPA datiPagamentoPA = objectFactory.createNodoTipoDatiPagamentoPA();
        datiPagamentoPA.setCausaleVersamento(causaleVersamento);
        datiPagamentoPA.setImportoSingoloVersamento(importoSingoloVersamento);
        datiPagamentoPA.setEnteBeneficiario(enteBeneficiario);
        datiPagamentoPA.setBicAccredito("AAAAA-BB-CC-123");
        datiPagamentoPA.setIbanAccredito("IT43J0300203280591787195952");

        FaultBean faultBean = objectFactory.createFaultBean();
        faultBean.setFaultCode(faultError);
        faultBean.setFaultString(faultError);
        esitoAttiva.setFault(faultBean);
        esitoAttiva.setDatiPagamentoPA(datiPagamentoPA);
        esitoAttiva.setEsito(esito);

        attivaRPTRisposta.setNodoAttivaRPTRisposta(esitoAttiva);


        /**
         * preconditions
         */

        when(nodoWebClient.post()).thenReturn((RequestBodyUriSpec) requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(), any(Object[].class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.header(any(), any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(), eq(SoapEnvelope.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(NodoAttivaRPTRisposta.class)).thenReturn(Mono.just(attivaRPTRisposta));

        /**
         * test
         */
        NodoAttivaRPTRisposta testResponse = client.attivaRPT(objectFactory.createNodoAttivaRPT(request)).block();

        /**
         * asserts
         */
        assertThat(testResponse.getNodoAttivaRPTRisposta().getEsito()).isEqualTo(esito);
        assertThat(testResponse.getNodoAttivaRPTRisposta().getFault().getFaultCode()).isEqualTo(faultError);
        assertThat(testResponse.getNodoAttivaRPTRisposta().getFault().getFaultString()).isEqualTo(faultError);
    }
}
