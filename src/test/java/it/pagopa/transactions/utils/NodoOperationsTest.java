package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.generated.nodoperpsp.model.*;
import it.pagopa.generated.transactions.model.*;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NodoPerPspClient;
import it.pagopa.transactions.configurations.NodoConfig;
import it.pagopa.transactions.exceptions.NodoErrorException;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class NodoOperationsTest {

    @InjectMocks
    private NodoOperations nodoOperations;

    @Mock
    NodoPerPspClient nodoPerPspClient;

    @Mock
    NodeForPspClient nodeForPspClient;

    @Mock
    NodoConfig nodoConfig;

    @Mock
    ActivatePaymentNoticeReq baseActivatePaymentNoticeReq;

    @Mock
    NodoAttivaRPT baseNodoAttivaRPT;

    @Mock
    it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

    @Mock
    it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp;

    @Mock
    NodoUtilities nodoUtilities;

    @Captor
    ArgumentCaptor<ActivatePaymentNoticeReq> activatePaymentNoticeReqArgumentCaptor;

    @Captor
    ArgumentCaptor<NodoAttivaRPT> nodoAttivaRPTArgumentCaptor;

    @Test
    void shouldActiveNM3PaymentRequest() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String paymentContextCode = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paName = "paName";
        String paTaxCode = "77777777777";
        String description = "Description";
        Integer amount = Integer.valueOf(1000);
        Boolean isNM3 = Boolean.TRUE;

        PaymentRequestInfo paymentRequestInfo = new PaymentRequestInfo(
                rptId,
                paTaxCode,
                paName,
                description,
                amount,
                null,
                isNM3,
                paymentToken,
                idempotencyKey
        );

        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";

        ActivatePaymentNoticeReq activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeReq();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeRes activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeRes();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);

        /** preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNotice(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(objectFactoryNodeForPsp.createActivatePaymentNoticeReq(Mockito.any()))
                .thenReturn(objectFactoryUtil.createActivatePaymentNoticeReq(activatePaymentReq));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeReq()).thenReturn(new ActivatePaymentNoticeReq());

        /** test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        Optional.of(paymentRequestInfo),
                        paymentContextCode,
                        amount,
                        false,
                        transactionId
                )
                .block();

        /** asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNotice(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
    }

    @Test
    void shouldNotActiveNM3PaymentRequestdueFaultError() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String paymentContextCode = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paName = "paName";
        String paTaxCode = "77777777777";
        String description = "Description";
        Integer amount = Integer.valueOf(1000);
        Boolean isNM3 = Boolean.TRUE;

        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";

        ActivatePaymentNoticeReq activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeReq();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeRes activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeRes();
        CtFaultBean ctFault = objectFactoryUtil.createCtFaultBean();
        ctFault.setFaultCode("PPT_PAGAMENTO_IN_CORSO");
        activatePaymentRes.setFault(ctFault);
        activatePaymentRes.setOutcome(StOutcome.KO);

        /** preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNotice(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(objectFactoryNodeForPsp.createActivatePaymentNoticeReq(Mockito.any()))
                .thenReturn(objectFactoryUtil.createActivatePaymentNoticeReq(activatePaymentReq));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeReq()).thenReturn(new ActivatePaymentNoticeReq());

        PaymentRequestInfo paymentRequestInfo = new PaymentRequestInfo(
                rptId,
                paTaxCode,
                paName,
                description,
                amount,
                null,
                isNM3,
                paymentToken,
                idempotencyKey
        );

        /** Test / asserts */
        Mono<PaymentRequestInfo> paymentRequestInfoMono = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        Optional.of(paymentRequestInfo),
                        paymentContextCode,
                        amount,
                        false,
                        transactionId
                );

        Assert.assertThrows(
                NodoErrorException.class,
                () -> {
                    paymentRequestInfoMono
                            .block();
                }
        );
    }

    @Test
    void shouldActiveNM3UnknownPaymentRequest() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String paymentContextCode = UUID.randomUUID().toString();
        String paymentNotice = "302000100000009424";
        String transactionId = UUID.randomUUID().toString();
        String paName = "paName";
        String paTaxCode = "77777777777";
        String description = "Description";
        Integer amount = Integer.valueOf(1000);
        Boolean isNM3 = Boolean.FALSE;

        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();

        it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryUtilNodoPerPsp = new it.pagopa.generated.nodoperpsp.model.ObjectFactory();

        BigDecimal amountBigDec = BigDecimal.valueOf(amount);

        ActivatePaymentNoticeReq activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeReq();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(paTaxCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeRes activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeRes();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(paTaxCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);

        NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = objectFactoryUtilNodoPerPsp.createNodoTipoCodiceIdRPT();
        QrCode qrCodeVerificaRPT = new QrCode();
        qrCodeVerificaRPT.setCF(paTaxCode);
        qrCodeVerificaRPT.setCodIUV(paymentNotice.substring(1));
        qrCodeVerificaRPT.setAuxDigit(paymentNotice.substring(0, 1));
        nodoTipoCodiceIdRPT.setQrCode(qrCodeVerificaRPT);
        NodoAttivaRPT nodoAttivaRPT = objectFactoryUtilNodoPerPsp.createNodoAttivaRPT();
        nodoAttivaRPT.setCodiceContestoPagamento(paymentContextCode);
        nodoAttivaRPT.setCodiceIdRPT(nodoTipoCodiceIdRPT);

        NodoAttivaRPTRisposta attivaRPTRisposta = objectFactoryUtilNodoPerPsp.createNodoAttivaRPTRisposta();
        EsitoNodoAttivaRPTRisposta esitoAttiva = objectFactoryUtilNodoPerPsp.createEsitoNodoAttivaRPTRisposta();
        NodoTipoDatiPagamentoPA datiPagamentoPA = objectFactoryUtilNodoPerPsp.createNodoTipoDatiPagamentoPA();
        datiPagamentoPA.setImportoSingoloVersamento(amountBigDec);

        esitoAttiva.setDatiPagamentoPA(datiPagamentoPA);
        esitoAttiva.setEsito("KO");
        FaultBean fault = objectFactoryUtilNodoPerPsp.createFaultBean();
        fault.setFaultCode("PPT_MULTI_BENEFICIARIO");
        esitoAttiva.setFault(fault);

        attivaRPTRisposta.setNodoAttivaRPTRisposta(esitoAttiva);

        /** preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNotice(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(objectFactoryNodeForPsp.createActivatePaymentNoticeReq(Mockito.any()))
                .thenReturn(objectFactoryUtil.createActivatePaymentNoticeReq(activatePaymentReq));
        Mockito.when(objectFactoryNodoPerPsp.createNodoTipoDatiPagamentoPSP())
                .thenReturn(objectFactoryUtilNodoPerPsp.createNodoTipoDatiPagamentoPSP());
        Mockito.when(nodoPerPspClient.attivaRPT(Mockito.any()))
                .thenReturn(Mono.just(attivaRPTRisposta));
        Mockito.when(objectFactoryNodoPerPsp.createNodoAttivaRPT(Mockito.any()))
                .thenReturn(objectFactoryUtilNodoPerPsp.createNodoAttivaRPT(nodoAttivaRPT));
        Mockito.when(nodoUtilities.getCodiceIdRpt(Mockito.any(RptId.class))).thenReturn(nodoTipoCodiceIdRPT);
        Mockito.when(nodoConfig.baseNodoAttivaRPTRequest()).thenReturn(new NodoAttivaRPT());
        Mockito.when(nodoConfig.baseActivatePaymentNoticeReq()).thenReturn(new ActivatePaymentNoticeReq());

        PaymentRequestInfo paymentRequestInfo = new PaymentRequestInfo(
                rptId,
                paTaxCode,
                paName,
                description,
                amount,
                null,
                isNM3,
                paymentToken,
                idempotencyKey
        );

        /** test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        Optional.of(paymentRequestInfo),
                        paymentContextCode,
                        amount,
                        false,
                        transactionId
                )
                .block();

        /** asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNotice(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
    }

    @Test
    void shouldFaultAttivaRPTUnknownPaymentRequest() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String paymentContextCode = UUID.randomUUID().toString();
        String paymentNotice = "302000100000009424";
        String transactionId = UUID.randomUUID().toString();
        String paName = "paName";
        String paTaxCode = "77777777777";
        String description = "Description";
        Integer amount = Integer.valueOf(1000);
        Boolean isNM3 = Boolean.FALSE;

        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();

        it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryUtilNodoPerPsp = new it.pagopa.generated.nodoperpsp.model.ObjectFactory();

        BigDecimal amountBigDec = BigDecimal.valueOf(amount);

        NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = objectFactoryUtilNodoPerPsp.createNodoTipoCodiceIdRPT();
        QrCode qrCodeVerificaRPT = new QrCode();
        qrCodeVerificaRPT.setCF(paTaxCode);
        qrCodeVerificaRPT.setCodIUV(paymentNotice.substring(1));
        qrCodeVerificaRPT.setAuxDigit(paymentNotice.substring(0, 1));
        nodoTipoCodiceIdRPT.setQrCode(qrCodeVerificaRPT);
        NodoAttivaRPT nodoAttivaRPT = objectFactoryUtilNodoPerPsp.createNodoAttivaRPT();
        nodoAttivaRPT.setCodiceContestoPagamento(paymentContextCode);
        nodoAttivaRPT.setCodiceIdRPT(nodoTipoCodiceIdRPT);

        NodoAttivaRPTRisposta attivaRPTRisposta = objectFactoryUtilNodoPerPsp.createNodoAttivaRPTRisposta();
        EsitoNodoAttivaRPTRisposta esitoAttiva = objectFactoryUtilNodoPerPsp.createEsitoNodoAttivaRPTRisposta();
        NodoTipoDatiPagamentoPA datiPagamentoPA = objectFactoryUtilNodoPerPsp.createNodoTipoDatiPagamentoPA();
        datiPagamentoPA.setImportoSingoloVersamento(amountBigDec);

        esitoAttiva.setDatiPagamentoPA(datiPagamentoPA);
        esitoAttiva.setEsito("KO");
        FaultBean fault = objectFactoryUtilNodoPerPsp.createFaultBean();
        fault.setFaultCode("PTT_PAGAMENTO_IN_CORSO");
        esitoAttiva.setFault(fault);

        attivaRPTRisposta.setNodoAttivaRPTRisposta(esitoAttiva);

        /** preconditions */
        Mockito.when(nodoUtilities.getCodiceIdRpt(Mockito.any(RptId.class)))
                .thenReturn(nodoTipoCodiceIdRPT);
        Mockito.when(objectFactoryNodoPerPsp.createNodoTipoDatiPagamentoPSP())
                .thenReturn(objectFactoryUtilNodoPerPsp.createNodoTipoDatiPagamentoPSP());
        Mockito.when(nodoPerPspClient.attivaRPT(Mockito.any()))
                .thenReturn(Mono.just(attivaRPTRisposta));
        Mockito.when(objectFactoryNodoPerPsp.createNodoAttivaRPT(Mockito.any()))
                .thenReturn(objectFactoryUtilNodoPerPsp.createNodoAttivaRPT(nodoAttivaRPT));
        Mockito.when(nodoConfig.baseNodoAttivaRPTRequest()).thenReturn(new NodoAttivaRPT());
        PaymentRequestInfo paymentRequestInfo = new PaymentRequestInfo(
                rptId,
                paTaxCode,
                paName,
                description,
                amount,
                null,
                isNM3,
                paymentToken,
                idempotencyKey
        );

        /** Test / asserts */

        Mono<PaymentRequestInfo> paymentRequestInfoMono = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        Optional.of(paymentRequestInfo),
                        paymentContextCode,
                        amount,
                        false,
                        transactionId
                );

        Assert.assertThrows(
                NodoErrorException.class,
                () -> {
                    paymentRequestInfoMono
                            .block();
                }
        );
    }

    @Test
    void shouldAttivaRPTNM3UnknownPaymentRequest() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String paymentContextCode = UUID.randomUUID().toString();
        String paymentNotice = "302000100000009424";
        String transactionId = UUID.randomUUID().toString();
        String paName = "paName";
        String paTaxCode = "77777777777";
        String description = "Description";
        Integer amount = Integer.valueOf(1000);
        Boolean isNM3 = Boolean.FALSE;

        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();

        it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryUtilNodoPerPsp = new it.pagopa.generated.nodoperpsp.model.ObjectFactory();

        BigDecimal amountBigDec = BigDecimal.valueOf(amount);

        ActivatePaymentNoticeReq activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeReq();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(paTaxCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = objectFactoryUtilNodoPerPsp.createNodoTipoCodiceIdRPT();
        QrCode qrCodeVerificaRPT = new QrCode();
        qrCodeVerificaRPT.setCF(paTaxCode);
        qrCodeVerificaRPT.setCodIUV(paymentNotice.substring(1));
        qrCodeVerificaRPT.setAuxDigit(paymentNotice.substring(0, 1));
        nodoTipoCodiceIdRPT.setQrCode(qrCodeVerificaRPT);
        NodoAttivaRPT nodoAttivaRPT = objectFactoryUtilNodoPerPsp.createNodoAttivaRPT();
        nodoAttivaRPT.setCodiceContestoPagamento(paymentContextCode);
        nodoAttivaRPT.setCodiceIdRPT(nodoTipoCodiceIdRPT);

        NodoAttivaRPTRisposta attivaRPTRisposta = objectFactoryUtilNodoPerPsp.createNodoAttivaRPTRisposta();
        EsitoNodoAttivaRPTRisposta esitoAttiva = objectFactoryUtilNodoPerPsp.createEsitoNodoAttivaRPTRisposta();
        NodoTipoDatiPagamentoPA datiPagamentoPA = objectFactoryUtilNodoPerPsp.createNodoTipoDatiPagamentoPA();
        datiPagamentoPA.setImportoSingoloVersamento(amountBigDec);

        CtEnteBeneficiario enteBeneficiario = objectFactoryUtilNodoPerPsp.createCtEnteBeneficiario();
        enteBeneficiario.setDenominazioneBeneficiario(paName);

        datiPagamentoPA.setEnteBeneficiario(enteBeneficiario);

        esitoAttiva.setDatiPagamentoPA(datiPagamentoPA);
        esitoAttiva.setEsito("OK");

        attivaRPTRisposta.setNodoAttivaRPTRisposta(esitoAttiva);

        /** preconditions */
        Mockito.when(objectFactoryNodoPerPsp.createNodoTipoDatiPagamentoPSP())
                .thenReturn(objectFactoryUtilNodoPerPsp.createNodoTipoDatiPagamentoPSP());
        Mockito.when(nodoPerPspClient.attivaRPT(Mockito.any()))
                .thenReturn(Mono.just(attivaRPTRisposta));
        Mockito.when(objectFactoryNodoPerPsp.createNodoAttivaRPT(Mockito.any()))
                .thenReturn(objectFactoryUtilNodoPerPsp.createNodoAttivaRPT(nodoAttivaRPT));
        Mockito.when(nodoUtilities.getCodiceIdRpt(Mockito.any(RptId.class))).thenReturn(nodoTipoCodiceIdRPT);
        Mockito.when(nodoConfig.baseNodoAttivaRPTRequest()).thenReturn(new NodoAttivaRPT());

        PaymentRequestInfo paymentRequestInfo = new PaymentRequestInfo(
                rptId,
                paTaxCode,
                paName,
                description,
                amount,
                null,
                isNM3,
                paymentToken,
                idempotencyKey
        );

        /** test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        Optional.of(paymentRequestInfo),
                        paymentContextCode,
                        amount,
                        false,
                        transactionId
                )
                .block();

        /** asserts */
        assertEquals(rptId, response.id());
    }

    @Test
    void shouldTrasformNodoAmountWithCentInEuroCent() {

        BigDecimal amountFromNodo = BigDecimal.valueOf(19.91);
        Integer amount = nodoOperations.getEuroCentsFromNodoAmount(amountFromNodo);
        assertEquals(1991, amount);
    }

    @Test
    void shouldTrasformNodoAmountWithoutCentInEuroCent() {

        BigDecimal amountFromNodo = BigDecimal.valueOf(19.00);
        Integer amount = nodoOperations.getEuroCentsFromNodoAmount(amountFromNodo);
        assertEquals(1900, amount);
    }

    @Test
    void shouldConvertAmountCorrectly() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String paymentContextCode = UUID.randomUUID().toString();
        String paymentNotice = "302000100000009424";
        String transactionId = UUID.randomUUID().toString();
        String paName = "paName";
        String paTaxCode = "77777777777";
        String description = "Description";
        Integer amount = Integer.valueOf(1234);
        Boolean isNM3 = Boolean.FALSE;

        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();

        it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryUtilNodoPerPsp = new it.pagopa.generated.nodoperpsp.model.ObjectFactory();

        BigDecimal amountBigDec = BigDecimal.valueOf(amount.doubleValue() / 100)
                .setScale(2, RoundingMode.CEILING);

        ActivatePaymentNoticeReq activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeReq();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(paTaxCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeRes activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeRes();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(paTaxCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);

        NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = objectFactoryUtilNodoPerPsp.createNodoTipoCodiceIdRPT();
        QrCode qrCodeVerificaRPT = new QrCode();
        qrCodeVerificaRPT.setCF(paTaxCode);
        qrCodeVerificaRPT.setCodIUV(paymentNotice.substring(1));
        qrCodeVerificaRPT.setAuxDigit(paymentNotice.substring(0, 1));
        nodoTipoCodiceIdRPT.setQrCode(qrCodeVerificaRPT);
        NodoAttivaRPT nodoAttivaRPT = objectFactoryUtilNodoPerPsp.createNodoAttivaRPT();
        nodoAttivaRPT.setCodiceContestoPagamento(paymentContextCode);
        nodoAttivaRPT.setCodiceIdRPT(nodoTipoCodiceIdRPT);

        NodoAttivaRPTRisposta attivaRPTRisposta = objectFactoryUtilNodoPerPsp.createNodoAttivaRPTRisposta();
        EsitoNodoAttivaRPTRisposta esitoAttiva = objectFactoryUtilNodoPerPsp.createEsitoNodoAttivaRPTRisposta();
        NodoTipoDatiPagamentoPA datiPagamentoPA = objectFactoryUtilNodoPerPsp.createNodoTipoDatiPagamentoPA();
        datiPagamentoPA.setImportoSingoloVersamento(amountBigDec);

        esitoAttiva.setDatiPagamentoPA(datiPagamentoPA);
        esitoAttiva.setEsito("KO");
        FaultBean fault = objectFactoryUtilNodoPerPsp.createFaultBean();
        fault.setFaultCode("PPT_MULTI_BENEFICIARIO");
        esitoAttiva.setFault(fault);

        attivaRPTRisposta.setNodoAttivaRPTRisposta(esitoAttiva);

        /** preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNotice(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp.createActivatePaymentNoticeReq(activatePaymentNoticeReqArgumentCaptor.capture())
        )
                .thenReturn(objectFactoryUtil.createActivatePaymentNoticeReq(activatePaymentReq));
        Mockito.when(objectFactoryNodoPerPsp.createNodoTipoDatiPagamentoPSP())
                .thenReturn(objectFactoryUtilNodoPerPsp.createNodoTipoDatiPagamentoPSP());
        Mockito.when(nodoPerPspClient.attivaRPT(Mockito.any()))
                .thenReturn(Mono.just(attivaRPTRisposta));
        Mockito.when(objectFactoryNodoPerPsp.createNodoAttivaRPT(nodoAttivaRPTArgumentCaptor.capture()))
                .thenReturn(objectFactoryUtilNodoPerPsp.createNodoAttivaRPT(nodoAttivaRPT));
        Mockito.when(nodoUtilities.getCodiceIdRpt(Mockito.any(RptId.class))).thenReturn(nodoTipoCodiceIdRPT);
        Mockito.when(nodoConfig.baseNodoAttivaRPTRequest()).thenReturn(new NodoAttivaRPT());
        Mockito.when(nodoConfig.baseActivatePaymentNoticeReq()).thenReturn(new ActivatePaymentNoticeReq());

        PaymentRequestInfo paymentRequestInfo = new PaymentRequestInfo(
                rptId,
                paTaxCode,
                paName,
                description,
                amount,
                null,
                isNM3,
                paymentToken,
                idempotencyKey
        );

        /** test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        Optional.of(paymentRequestInfo),
                        paymentContextCode,
                        amount,
                        false,
                        transactionId
                )
                .block();

        /** asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNotice(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        // check amount saved into PaymentRequestInfo object
        assertEquals(1234, response.amount());
        // Check amount sent into Nodo requests
        assertEquals(
                BigDecimal.valueOf(12.34).doubleValue(),
                nodoAttivaRPTArgumentCaptor.getValue().getDatiPagamentoPSP().getImportoSingoloVersamento().doubleValue()
        );
        assertEquals(
                BigDecimal.valueOf(12.34).doubleValue(),
                activatePaymentNoticeReqArgumentCaptor.getValue().getAmount().doubleValue()
        );
    }
}
