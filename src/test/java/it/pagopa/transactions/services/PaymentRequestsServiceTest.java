package it.pagopa.transactions.services;

import it.pagopa.generated.nodoperpsp.model.*;
import it.pagopa.generated.payment.requests.model.PaymentRequestsGetResponseDto;
import it.pagopa.generated.payment.requests.model.PaymentStatusFaultDto;
import it.pagopa.generated.payment.requests.model.ValidationFaultDto;
import it.pagopa.generated.transactions.model.*;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NodoPerPspClient;
import it.pagopa.transactions.domain.RptId;
import it.pagopa.transactions.exceptions.NodoErrorException;
import it.pagopa.transactions.repositories.PaymentRequestInfo;
import it.pagopa.transactions.repositories.PaymentRequestsInfoRepository;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

  @Test
  void shouldReturnPaymentInfoRequestFromCache() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);
    final String paTaxCode = "77777777777";
    final String paName = "Pa Name";
    final String description = "Payment request description";
    final BigDecimal amount = BigDecimal.valueOf(1000);

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
    assertEquals(responseDto.getRptId(), rptIdAsString);
    assertEquals(responseDto.getDescription(), description);
    assertEquals(responseDto.getDueDate(), null);
    assertEquals(BigDecimal.valueOf(responseDto.getAmount()), amount);
    assertEquals(responseDto.getPaName(), paName);
    assertEquals(responseDto.getPaTaxCode(), paTaxCode);
  }

  @Test
  void shouldReturnPaymentInfoRequestFromNodoVerificaRPT() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);
    final String paTaxCode = "77777777777";
    final String paName = "Pa Name";
    final String description = "Payment request description";
    final BigDecimal amount = BigDecimal.valueOf(1000);

    PaymentRequestInfo paymentRequestInfo =
        new PaymentRequestInfo(
            rptIdAsObject, paTaxCode, paName, description, amount, null, true, null, null);

    NodoVerificaRPTRisposta verificaRPTRIsposta = new NodoVerificaRPTRisposta();
    EsitoNodoVerificaRPTRisposta esitoVerificaRPT = new EsitoNodoVerificaRPTRisposta();
    esitoVerificaRPT.setEsito(StOutcome.OK.value());
    NodoTipoDatiPagamentoPA datiPagamento = new NodoTipoDatiPagamentoPA();
    datiPagamento.setCausaleVersamento(description);
    datiPagamento.setImportoSingoloVersamento(amount);
    esitoVerificaRPT.setDatiPagamentoPA(datiPagamento);
    verificaRPTRIsposta.setNodoVerificaRPTRisposta(esitoVerificaRPT);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
        .thenReturn(Optional.empty());
    Mockito.when(objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT())
        .thenReturn(new NodoTipoCodiceIdRPT());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));

    /** Test */
    PaymentRequestsGetResponseDto responseDto =
        paymentRequestsService.getPaymentRequestInfo(rptIdAsString).block();

    /** Assertions */
    assertEquals(responseDto.getRptId(), rptIdAsString);
    assertEquals(responseDto.getDescription(), description);
    assertEquals(null, responseDto.getDueDate());
    assertEquals(BigDecimal.valueOf(responseDto.getAmount()), amount);
  }

  @Test
  void shouldReturnPaymentInfoRequestFromNodoVerificaRPTWithEnteBeneficiario() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);
    final String paTaxCode = "77777777777";
    final String paName = "Pa Name";
    final String description = "Payment request description";
    final BigDecimal amount = BigDecimal.valueOf(1000);

    NodoVerificaRPTRisposta verificaRPTRIsposta = new NodoVerificaRPTRisposta();
    EsitoNodoVerificaRPTRisposta esitoVerificaRPT = new EsitoNodoVerificaRPTRisposta();
    esitoVerificaRPT.setEsito(StOutcome.OK.value());
    NodoTipoDatiPagamentoPA datiPagamento = new NodoTipoDatiPagamentoPA();
    datiPagamento.setCausaleVersamento(description);
    datiPagamento.setImportoSingoloVersamento(amount);
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
    Mockito.when(objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT())
        .thenReturn(new NodoTipoCodiceIdRPT());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));

    /** Test */
    PaymentRequestsGetResponseDto responseDto =
        paymentRequestsService.getPaymentRequestInfo(rptIdAsString).block();

    /** Assertions */
    assertEquals(responseDto.getRptId(), rptIdAsString);
    assertEquals(responseDto.getDescription(), description);
    assertEquals(null, responseDto.getDueDate());
    assertEquals(BigDecimal.valueOf(responseDto.getAmount()), amount);
    assertEquals(responseDto.getPaName(), paName);
    assertEquals(responseDto.getPaTaxCode(), paTaxCode);
  }

  @Test
  void shouldReturnPaymentInfoRequestFromNodoVerifyPaymentNotice() {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);
    final String paTaxCode = "77777777777";
    final String paName = "Pa Name";
    final String description = "Payment request description";
    final BigDecimal amount = BigDecimal.valueOf(1000);

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
    paymentDescription.setAmount(amount);
    paymentList.getPaymentOptionDescription().add(paymentDescription);
    verifyPaymentNotice.setPaymentList(paymentList);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
        .thenReturn(Optional.empty());
    Mockito.when(objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT())
        .thenReturn(new NodoTipoCodiceIdRPT());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));
    Mockito.when(nodeForPspClient.verifyPaymentNotice(Mockito.any()))
        .thenReturn(Mono.just(verifyPaymentNotice));
    /** Test */
    PaymentRequestsGetResponseDto responseDto =
        paymentRequestsService.getPaymentRequestInfo(rptIdAsString).block();

    /** Assertions */
    assertEquals(responseDto.getRptId(), rptIdAsString);
    assertEquals(responseDto.getDescription(), description);
    assertEquals(null, responseDto.getDueDate());
    assertEquals(BigDecimal.valueOf(responseDto.getAmount()), amount);
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
    Mockito.when(objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT())
        .thenReturn(new NodoTipoCodiceIdRPT());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));

    /** Test */
    Assert.assertThrows(
        NodoErrorException.class,
        () -> paymentRequestsService.getPaymentRequestInfo(rptIdAsString).block());
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
    Mockito.when(objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT())
        .thenReturn(new NodoTipoCodiceIdRPT());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));

    /** Test */
    Assert.assertThrows(
        NodoErrorException.class,
        () -> paymentRequestsService.getPaymentRequestInfo(rptIdAsString).block());
  }

  @Test
  void shouldReturnPaymentInfoRequestFromNodoVerifyPaymentNoticeWithDueDate()
      throws ParseException, DatatypeConfigurationException {
    final String rptIdAsString = "77777777777302016723749670035";
    final RptId rptIdAsObject = new RptId(rptIdAsString);
    final String paTaxCode = "77777777777";
    final String paName = "Pa Name";
    final String description = "Payment request description";
    final BigDecimal amount = BigDecimal.valueOf(1000);

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
    paymentDescription.setAmount(amount);
    paymentDescription.setDueDate(dueDate);
    paymentList.getPaymentOptionDescription().add(paymentDescription);
    verifyPaymentNotice.setPaymentList(paymentList);

    /** Preconditions */
    Mockito.when(paymentRequestsInfoRepository.findById(rptIdAsObject))
        .thenReturn(Optional.empty());
    Mockito.when(objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT())
        .thenReturn(new NodoTipoCodiceIdRPT());
    Mockito.when(nodoPerPspClient.verificaRPT(Mockito.any()))
        .thenReturn(Mono.just(verificaRPTRIsposta));
    Mockito.when(nodeForPspClient.verifyPaymentNotice(Mockito.any()))
        .thenReturn(Mono.just(verifyPaymentNotice));
    /** Test */
    PaymentRequestsGetResponseDto responseDto =
        paymentRequestsService.getPaymentRequestInfo(rptIdAsString).block();

    /** Assertions */
    assertEquals(responseDto.getRptId(), rptIdAsString);
    assertEquals(responseDto.getDescription(), description);
    assertEquals("2022-04-24", responseDto.getDueDate());
    assertEquals(BigDecimal.valueOf(responseDto.getAmount()), amount);
  }
}
