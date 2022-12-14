package it.pagopa.transactions.services;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestsInfoRepository;
import it.pagopa.generated.nodoperpsp.model.*;
import it.pagopa.generated.payment.requests.model.PaymentRequestsGetResponseDto;
import it.pagopa.generated.payment.requests.model.PaymentStatusFaultDto;
import it.pagopa.generated.payment.requests.model.ValidationFaultDto;
import it.pagopa.generated.transactions.model.*;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NodoPerPspClient;
import it.pagopa.transactions.exceptions.NodoErrorException;
import it.pagopa.transactions.utils.NodoOperations;
import it.pagopa.transactions.utils.NodoUtilities;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
class PaymentRequestsServiceTest {

  @InjectMocks private PaymentRequestsService paymentRequestsService;

  @Mock private PaymentRequestsInfoRepository paymentRequestsInfoRepository;

  @Mock private NodoPerPspClient nodoPerPspClient;

  @Mock private NodeForPspClient nodeForPspClient;

  @Mock private it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp;

  @Mock private it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

  @Mock private NodoVerificaRPT baseNodoVerificaRPTRequest;

  @Mock private VerifyPaymentNoticeReq baseVerifyPaymentNoticeReq;

  @Mock private NodoOperations nodoOperations;
  @Mock private NodoUtilities nodoUtilities;

  @Test
  void shouldReturnPaymentInfoRequestFromCache() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);
    final String paTaxCode = "77777777777";
    final String paName = "Pa Name";
    final String description = "Payment request description";
    final Integer amount = Integer.valueOf(1000);

    PaymentRequestInfo paymentRequestInfo =
        new PaymentRequestInfo(
            rptIdAsObject, paTaxCode, paName, description, amount, null, true, null, null);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
        .thenReturn(Optional.of(paymentRequestInfo));

    /** Test */
    PaymentRequestsGetResponseDto responseDto =
        paymentRequestsService.getPaymentRequestInfo(rptIdAsString).block();

    /** Assertions */
    assertEquals(rptIdAsString, responseDto.getRptId());
    assertEquals(description, responseDto.getDescription());
    assertNull(responseDto.getDueDate());
    assertEquals(amount, responseDto.getAmount());
    assertEquals(paName, responseDto.getPaName());
    assertEquals(paTaxCode, responseDto.getPaFiscalCode());
  }

  @Test
  void shouldReturnPaymentInfoRequestFromNodoVerificaRPT() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);
    final String paTaxCode = "77777777777";
    final String paName = "Pa Name";
    final String description = "Payment request description";
    final Integer amount = 1000;
    final BigDecimal amountForNodo = BigDecimal.valueOf(amount);

    PaymentRequestInfo paymentRequestInfo =
        new PaymentRequestInfo(
            rptIdAsObject, paTaxCode, paName, description, amount, null, true, null, null);

    NodoVerificaRPTRisposta verificaRPTRIsposta = new NodoVerificaRPTRisposta();
    EsitoNodoVerificaRPTRisposta esitoVerificaRPT = new EsitoNodoVerificaRPTRisposta();
    esitoVerificaRPT.setEsito(StOutcome.OK.value());
    NodoTipoDatiPagamentoPA datiPagamento = new NodoTipoDatiPagamentoPA();
    datiPagamento.setCausaleVersamento(description);
    datiPagamento.setImportoSingoloVersamento(amountForNodo);
    esitoVerificaRPT.setDatiPagamentoPA(datiPagamento);
    verificaRPTRIsposta.setNodoVerificaRPTRisposta(esitoVerificaRPT);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
        .thenReturn(Optional.empty());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));
    Mockito.when(nodoOperations.getEuroCentsFromNodoAmount(amountForNodo))
            .thenReturn(amount);

    /** Test */
    PaymentRequestsGetResponseDto responseDto =
        paymentRequestsService.getPaymentRequestInfo(rptIdAsString).block();

    /** Assertions */
    assertEquals(responseDto.getRptId(), rptIdAsString);
    assertEquals(responseDto.getDescription(), description);
    assertEquals(null, responseDto.getDueDate());
    assertEquals(responseDto.getAmount(), amount);
  }

  @Test
  void shouldReturnPaymentInfoRequestFromNodoVerificaRPTWithEnteBeneficiario() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);
    final String paTaxCode = "77777777777";
    final String paName = "Pa Name";
    final String description = "Payment request description";
    final Integer amount = 1000;
    final BigDecimal amountForNodo = BigDecimal.valueOf(amount);

    NodoVerificaRPTRisposta verificaRPTRIsposta = new NodoVerificaRPTRisposta();
    EsitoNodoVerificaRPTRisposta esitoVerificaRPT = new EsitoNodoVerificaRPTRisposta();
    esitoVerificaRPT.setEsito(StOutcome.OK.value());
    NodoTipoDatiPagamentoPA datiPagamento = new NodoTipoDatiPagamentoPA();
    datiPagamento.setCausaleVersamento(description);
    datiPagamento.setImportoSingoloVersamento(amountForNodo);
    CtEnteBeneficiario ente = new CtEnteBeneficiario();
    ente.setDenominazioneBeneficiario(paName);
    CtIdentificativoUnivocoPersonaG paId = new CtIdentificativoUnivocoPersonaG();
    paId.setCodiceIdentificativoUnivoco(paTaxCode);
    ente.setIdentificativoUnivocoBeneficiario(paId);
    datiPagamento.setEnteBeneficiario(ente);
    esitoVerificaRPT.setDatiPagamentoPA(datiPagamento);
    verificaRPTRIsposta.setNodoVerificaRPTRisposta(esitoVerificaRPT);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
        .thenReturn(Optional.empty());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));
    Mockito.when(nodoOperations.getEuroCentsFromNodoAmount(amountForNodo))
            .thenReturn(amount);

    /** Test */
    PaymentRequestsGetResponseDto responseDto =
        paymentRequestsService.getPaymentRequestInfo(rptIdAsString).block();

    /** Assertions */
    assertEquals(responseDto.getRptId(), rptIdAsString);
    assertEquals(responseDto.getDescription(), description);
    assertEquals(null, responseDto.getDueDate());
    assertEquals(responseDto.getAmount(), amount);
    assertEquals(responseDto.getPaName(), paName);
    assertEquals(responseDto.getPaFiscalCode(), paTaxCode);
  }

  @Test
  void shouldReturnPaymentInfoRequestFromNodoVerifyPaymentNotice() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);
    final String paTaxCode = "77777777777";
    final String paName = "Pa Name";
    final String description = "Payment request description";
    final Integer amount = 1000;
    final BigDecimal amountForNodo = BigDecimal.valueOf(amount);

    PaymentRequestInfo paymentRequestInfo =
        new PaymentRequestInfo(
            rptIdAsObject, paTaxCode, paName, description, amount, null, true, null, null);

    NodoVerificaRPTRisposta verificaRPTRIsposta = new NodoVerificaRPTRisposta();
    EsitoNodoVerificaRPTRisposta esitoVerificaRPT = new EsitoNodoVerificaRPTRisposta();
    esitoVerificaRPT.setEsito(StOutcome.KO.value());
    FaultBean fault = new FaultBean();
    fault.setFaultCode("PPT_MULTI_BENEFICIARIO");
    esitoVerificaRPT.setFault(fault);
    verificaRPTRIsposta.setNodoVerificaRPTRisposta(esitoVerificaRPT);

    VerifyPaymentNoticeRes verifyPaymentNotice = new VerifyPaymentNoticeRes();
    verifyPaymentNotice.setOutcome(StOutcome.OK);
    verifyPaymentNotice.setPaymentDescription(description);
    CtPaymentOptionsDescriptionList paymentList = new CtPaymentOptionsDescriptionList();
    CtPaymentOptionDescription paymentDescription = new CtPaymentOptionDescription();
    paymentDescription.setAmount(amountForNodo);
    paymentList.getPaymentOptionDescription().add(paymentDescription);
    verifyPaymentNotice.setPaymentList(paymentList);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
        .thenReturn(Optional.empty());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));
    Mockito.when(nodeForPspClient.verifyPaymentNotice(Mockito.any()))
        .thenReturn(Mono.just(verifyPaymentNotice));
    Mockito.when(nodoOperations.getEuroCentsFromNodoAmount(amountForNodo))
            .thenReturn(amount);

    /** Test */
    PaymentRequestsGetResponseDto responseDto =
        paymentRequestsService.getPaymentRequestInfo(rptIdAsString).block();

    /** Assertions */
    assertEquals(responseDto.getRptId(), rptIdAsString);
    assertEquals(responseDto.getDescription(), description);
    assertEquals(null, responseDto.getDueDate());
    assertEquals(responseDto.getAmount(), amount);
  }

  @Test
  void shouldReturnPaymentOngoingFromNodoVerificaRPT() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);

    NodoVerificaRPTRisposta verificaRPTRIsposta = new NodoVerificaRPTRisposta();
    EsitoNodoVerificaRPTRisposta esitoVerificaRPT = new EsitoNodoVerificaRPTRisposta();
    esitoVerificaRPT.setEsito(StOutcome.KO.value());
    FaultBean fault = new FaultBean();
    fault.setFaultCode(PaymentStatusFaultDto.PPT_PAGAMENTO_IN_CORSO.getValue());
    esitoVerificaRPT.setFault(fault);
    verificaRPTRIsposta.setNodoVerificaRPTRisposta(esitoVerificaRPT);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
        .thenReturn(Optional.empty());
    Mockito.when(nodoUtilities.getCodiceIdRpt(Mockito.any(RptId.class)))
        .thenReturn(new NodoTipoCodiceIdRPT());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));

    /** Test */
    Mono<PaymentRequestsGetResponseDto> paymentRequestInfoMono = paymentRequestsService.getPaymentRequestInfo(rptIdAsString);
    Assert.assertThrows(
        NodoErrorException.class,
        () -> {
          paymentRequestInfoMono.block();
        });
  }

  @Test
  void shouldReturnPaymentUnknowFromNodoVerificaRPT() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);

    NodoVerificaRPTRisposta verificaRPTRIsposta = new NodoVerificaRPTRisposta();
    EsitoNodoVerificaRPTRisposta esitoVerificaRPT = new EsitoNodoVerificaRPTRisposta();
    esitoVerificaRPT.setEsito(StOutcome.KO.value());
    FaultBean fault = new FaultBean();
    fault.setFaultCode(ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue());
    esitoVerificaRPT.setFault(fault);
    verificaRPTRIsposta.setNodoVerificaRPTRisposta(esitoVerificaRPT);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
        .thenReturn(Optional.empty());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));

    /** Test */
    Mono<PaymentRequestsGetResponseDto> paymentRequestInfoMono = paymentRequestsService.getPaymentRequestInfo(rptIdAsString);
    Assert.assertThrows(
        NodoErrorException.class,
        () -> {
          paymentRequestInfoMono.block();
        });
  }

  @Test
  void shouldReturnPaymentInfoRequestFromNodoVerifyPaymentNoticeWithDueDate()
      throws ParseException, DatatypeConfigurationException {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);
    final String paTaxCode = "77777777777";
    final String paName = "Pa Name";
    final String description = "Payment request description";
    final Integer amount = 1000;
    final BigDecimal amountForNodo = BigDecimal.valueOf(amount);

    DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
    Date date = format.parse("2022-04-24");
    XMLGregorianCalendar dueDate =
        DatatypeFactory.newInstance().newXMLGregorianCalendar(format.format(date));

    NodoVerificaRPTRisposta verificaRPTRIsposta = new NodoVerificaRPTRisposta();
    EsitoNodoVerificaRPTRisposta esitoVerificaRPT = new EsitoNodoVerificaRPTRisposta();
    esitoVerificaRPT.setEsito(StOutcome.KO.value());
    FaultBean fault = new FaultBean();
    fault.setFaultCode("PPT_MULTI_BENEFICIARIO");
    esitoVerificaRPT.setFault(fault);
    verificaRPTRIsposta.setNodoVerificaRPTRisposta(esitoVerificaRPT);

    VerifyPaymentNoticeRes verifyPaymentNotice = new VerifyPaymentNoticeRes();
    verifyPaymentNotice.setOutcome(StOutcome.OK);
    verifyPaymentNotice.setPaymentDescription(description);
    CtPaymentOptionsDescriptionList paymentList = new CtPaymentOptionsDescriptionList();
    CtPaymentOptionDescription paymentDescription = new CtPaymentOptionDescription();
    paymentDescription.setAmount(amountForNodo);
    paymentDescription.setDueDate(dueDate);
    paymentList.getPaymentOptionDescription().add(paymentDescription);
    verifyPaymentNotice.setPaymentList(paymentList);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
        .thenReturn(Optional.empty());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));
    Mockito.when(nodeForPspClient.verifyPaymentNotice(Mockito.any()))
        .thenReturn(Mono.just(verifyPaymentNotice));
    Mockito.when(nodoOperations.getEuroCentsFromNodoAmount(amountForNodo))
            .thenReturn(amount);

    /** Test */
    PaymentRequestsGetResponseDto responseDto =
        paymentRequestsService.getPaymentRequestInfo(rptIdAsString).block();

    /** Assertions */
    assertEquals(responseDto.getRptId(), rptIdAsString);
    assertEquals(responseDto.getDescription(), description);
    assertEquals("2022-04-24", responseDto.getDueDate());
    assertEquals(responseDto.getAmount(), amount);
  }

  @Test
  void shouldGetFaultFromCode() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);

    NodoVerificaRPTRisposta verificaRPTRIsposta = new NodoVerificaRPTRisposta();
    EsitoNodoVerificaRPTRisposta esitoVerificaRPT = new EsitoNodoVerificaRPTRisposta();
    esitoVerificaRPT.setEsito(StOutcome.KO.value());
    FaultBean fault = new FaultBean();
    fault.setFaultCode("PPT_ERRORE_EMESSO_DA_PAA");

    esitoVerificaRPT.setFault(fault);
    verificaRPTRIsposta.setNodoVerificaRPTRisposta(esitoVerificaRPT);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
            .thenReturn(Optional.empty());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
            .thenReturn(Mono.just(verificaRPTRIsposta));

    /** Assertions */
    StepVerifier
            .create(paymentRequestsService.getPaymentRequestInfo(rptIdAsString))
            .expectErrorMatches(t -> t instanceof NodoErrorException && ((NodoErrorException) t).getFaultCode().equals("PPT_ERRORE_EMESSO_DA_PAA"))
            .verify();
  }

  @Test
  void shouldFallbackOnFaultCodeOnDescriptionWithoutFaultCode() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);

    NodoVerificaRPTRisposta verificaRPTRIsposta = new NodoVerificaRPTRisposta();
    EsitoNodoVerificaRPTRisposta esitoVerificaRPT = new EsitoNodoVerificaRPTRisposta();
    esitoVerificaRPT.setEsito(StOutcome.KO.value());
    FaultBean fault = new FaultBean();
    fault.setFaultCode("PPT_ERRORE_EMESSO_DA_PAA");
    fault.setDescription("");

    esitoVerificaRPT.setFault(fault);
    verificaRPTRIsposta.setNodoVerificaRPTRisposta(esitoVerificaRPT);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
            .thenReturn(Optional.empty());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
            .thenReturn(Mono.just(verificaRPTRIsposta));

    /** Assertions */
    StepVerifier
            .create(paymentRequestsService.getPaymentRequestInfo(rptIdAsString))
            .expectErrorMatches(t -> t instanceof NodoErrorException && ((NodoErrorException) t).getFaultCode().equals("PPT_ERRORE_EMESSO_DA_PAA"))
            .verify();
  }

  @Test
  void shouldGetFaultFromDescriptionIfPresent() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);

    NodoVerificaRPTRisposta verificaRPTRIsposta = new NodoVerificaRPTRisposta();
    EsitoNodoVerificaRPTRisposta esitoVerificaRPT = new EsitoNodoVerificaRPTRisposta();
    esitoVerificaRPT.setEsito(StOutcome.KO.value());
    FaultBean fault = new FaultBean();
    fault.setFaultCode("PPT_ERRORE_EMESSO_DA_PAA");
    fault.setDescription("""
            FaultString PA: Pagamento in attesa risulta concluso all’Ente Creditore
            FaultCode PA: PAA_PAGAMENTO_DUPLICATO
            Description PA:
            """);

    esitoVerificaRPT.setFault(fault);
    verificaRPTRIsposta.setNodoVerificaRPTRisposta(esitoVerificaRPT);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
            .thenReturn(Optional.empty());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
            .thenReturn(Mono.just(verificaRPTRIsposta));

    /** Assertions */
    StepVerifier
            .create(paymentRequestsService.getPaymentRequestInfo(rptIdAsString))
            .expectErrorMatches(t -> t instanceof NodoErrorException && ((NodoErrorException) t).getFaultCode().equals("PAA_PAGAMENTO_DUPLICATO"))
            .verify();
  }
}
