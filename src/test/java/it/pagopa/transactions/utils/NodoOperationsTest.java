package it.pagopa.transactions.utils;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import it.pagopa.ecommerce.commons.domain.v1.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.v1.RptId;
import it.pagopa.ecommerce.commons.repositories.v1.PaymentRequestInfo;
import it.pagopa.generated.transactions.model.*;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.configurations.NodoConfig;
import it.pagopa.transactions.exceptions.InvalidNodoResponseException;
import it.pagopa.transactions.exceptions.NodoErrorException;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class NodoOperationsTest {
    public static final String IBANAPPOGGIO = "IBANAPPOGGIO";
    private NodoOperations nodoOperations;

    @Mock
    NodeForPspClient nodeForPspClient;

    @Mock
    NodoConfig nodoConfig;

    @Mock
    it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

    @Captor
    ArgumentCaptor<ActivatePaymentNoticeV2Request> activatePaymentNoticeReqArgumentCaptor;

    private final String dueDate = "2031-12-31";

    private final OpenTelemetryUtils openTelemetryUtils = Mockito.mock(OpenTelemetryUtils.class);

    void instantiateNodoOperations(boolean lightAllCCPCheck) {
        nodoOperations = new NodoOperations(
                nodeForPspClient,
                objectFactoryNodeForPsp,
                nodoConfig,
                lightAllCCPCheck,
                openTelemetryUtils
        );
    }

    @Test
    void shouldActiveNM3PaymentRequest() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
        Mockito.verify(openTelemetryUtils, Mockito.times(1)).addSpanWithAttributes(
                OpenTelemetryUtils.NODO_ACTIVATION_OK_SPAN_NAME,
                Attributes
                        .of(
                                AttributeKey.stringKey(
                                        OpenTelemetryUtils.NODO_ACTIVATION_ERROR_FAULT_CODE_ATTRIBUTE_KEY
                                ),
                                StOutcome.OK.toString()
                        )

        );
    }

    @Test
    void shouldActiveNM3PaymentRequestWithFieldIbanAppoggioWithoutWantedABI() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(IBANAPPOGGIO);
        ctMapEntry.setValue("IT41B0000100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey(IBANAPPOGGIO);
        ctMapEntry_1.setValue("IT41B0000100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithNotAllMatchClause() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(IBANAPPOGGIO);
        ctMapEntry.setValue("IT41B0000100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey(IBANAPPOGGIO);
        ctMapEntry_1.setValue("IT41B00001008998761132355672");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void allCCPTrue_LightWeightCheckTrue() {
        instantiateNodoOperations(true);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");

        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setIBAN("IT22U0760100899996122235789");
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(true, response.isAllCCP());
    }

    @Test
    void allCCPFalse_LightWeightCheckTrue_TransferWithoutIBAN() {
        instantiateNodoOperations(true);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");

        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);

        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void allCCPFalse_LightWeightCheckTrue_IbanNotValid() {
        instantiateNodoOperations(true);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");

        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setIBAN("IT22U0760200899996122235789");
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void allCCPFalse_LightWeightCheckTrue_NoIbanAndIBANAPPOGGIOPresent() {
        instantiateNodoOperations(true);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(IBANAPPOGGIO);
        ctMapEntry.setValue("IT41B0000100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);

        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setIBAN("IT22U0760200899996122235789");
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey(IBANAPPOGGIO);
        ctMapEntry_1.setValue("IT20U0760100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);

        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithFieldIbanAppoggioWithOnlyOneWantedABI() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(IBANAPPOGGIO);
        ctMapEntry.setValue("IT20U0760100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey(IBANAPPOGGIO);
        ctMapEntry_1.setValue("IT41B00060100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithBothFieldIbanAppoggioWithWantedABI() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(IBANAPPOGGIO);
        ctMapEntry.setValue("IT20U0760100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey(IBANAPPOGGIO);
        ctMapEntry_1.setValue("IT20U0760100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(true, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithBothFieldIbanAppoggioWithUnwantedMetadata() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(IBANAPPOGGIO);
        ctMapEntry.setValue("IT20U0760100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey("test_key");
        ctMapEntry_1.setValue("test_value");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestIsIbanFailOnMetadata() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(IBANAPPOGGIO);
        ctMapEntry.setValue("IT20U0760100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey(IBANAPPOGGIO);
        ctMapEntry_1.setValue(null);
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestIsIbanFailTooShort() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(IBANAPPOGGIO);
        ctMapEntry.setValue("IT20U0760");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey(IBANAPPOGGIO);
        ctMapEntry_1.setValue("IT20U076010000000000089987611323556");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithBothFieldIbanAppoggioWithWantedABIOnTransferIban() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(IBANAPPOGGIO);
        ctMapEntry.setValue("IT41B1230100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey(IBANAPPOGGIO);
        ctMapEntry_1.setValue("IT20U0850100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(true, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithBothFieldIbanAppoggioWithWantedABIOnTransferIbanAndIbanAppoggio() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(IBANAPPOGGIO);
        ctMapEntry.setValue("IT41B0000100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey(IBANAPPOGGIO);
        ctMapEntry_1.setValue("IT20U0760100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(true, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithIdCartNull() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote() == null))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        null,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
    }

    @Test
    void shouldNotActiveNM3PaymentRequestdueFaultError() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String transactionId = UUID.randomUUID().toString();
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";

        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        CtFaultBean ctFault = objectFactoryUtil.createCtFaultBean();
        ctFault.setFaultCode("PPT_PAGAMENTO_IN_CORSO");
        activatePaymentRes.setFault(ctFault);
        activatePaymentRes.setOutcome(StOutcome.KO);

        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(objectFactoryNodeForPsp.createActivatePaymentNoticeV2Request(Mockito.any()))
                .thenReturn(objectFactoryUtil.createActivatePaymentNoticeV2Request(activatePaymentReq));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* Test / asserts */
        Mono<PaymentRequestInfo> paymentRequestInfoMono = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                );

        Assert.assertThrows(
                NodoErrorException.class,
                paymentRequestInfoMono::block
        );
    }

    @Test
    void shouldNotActiveNM3PaymentRequestForMissingPaymentToken() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String transactionId = UUID.randomUUID().toString();
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";

        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(null);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);

        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(objectFactoryNodeForPsp.createActivatePaymentNoticeV2Request(Mockito.any()))
                .thenReturn(objectFactoryUtil.createActivatePaymentNoticeV2Request(activatePaymentReq));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* Test / asserts */
        Mono<PaymentRequestInfo> paymentRequestInfoMono = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                );

        InvalidNodoResponseException exception = Assert.assertThrows(
                InvalidNodoResponseException.class,
                paymentRequestInfoMono::block
        );
        assertEquals("No payment token received", exception.getErrorDescription());
    }

    @Test
    void shouldTrasformNodoAmountWithCentInEuroCent() {
        instantiateNodoOperations(false);
        BigDecimal amountFromNodo = BigDecimal.valueOf(19.91);
        Integer amount = nodoOperations.getEuroCentsFromNodoAmount(amountFromNodo);
        assertEquals(1991, amount);
    }

    @Test
    void shouldTrasformNodoAmountWithoutCentInEuroCent() {
        instantiateNodoOperations(false);
        BigDecimal amountFromNodo = BigDecimal.valueOf(19.00);
        Integer amount = nodoOperations.getEuroCentsFromNodoAmount(amountFromNodo);
        assertEquals(1900, amount);
    }

    @Test
    void shouldConvertAmountCorrectly() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String paymentNotice = "302000100000009424";
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        Integer amount = 1234;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();

        BigDecimal amountBigDec = BigDecimal.valueOf(amount.doubleValue() / 100)
                .setScale(2, RoundingMode.CEILING);

        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(paTaxCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(paTaxCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(objectFactoryUtil.createCtTransferListPSPV2());

        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(activatePaymentNoticeReqArgumentCaptor.capture())
        )
                .thenReturn(objectFactoryUtil.createActivatePaymentNoticeV2Request(activatePaymentReq));

        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

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
                activatePaymentNoticeReqArgumentCaptor.getValue().getAmount().doubleValue()
        );
    }

    @Test
    void shouldReturnFiscalCodeEcommerce() {

        /* preconditions */
        instantiateNodoOperations(false);
        String ecommerceFiscalCode = "00000000000";
        NodoConnectionString nodoConnectionString = new NodoConnectionString();
        nodoConnectionString.setIdBrokerPSP(ecommerceFiscalCode);
        Mockito.when(nodoConfig.nodoConnectionString()).thenReturn(nodoConnectionString);

        /* test */
        String maybeEcommerceFiscalCode = nodoOperations
                .getEcommerceFiscalCode();

        /* asserts */
        assertEquals(ecommerceFiscalCode, maybeEcommerceFiscalCode);
    }

    @Test
    void shouldReturnRandomStringforIdempotencykey() {
        instantiateNodoOperations(false);
        /* test */
        String randomStringToIdempotencyKey = nodoOperations
                .generateRandomStringToIdempotencyKey();

        /* asserts */
        assertEquals(10, randomStringToIdempotencyKey.length());
    }

    @Test
    void shouldGetTheUpdatedAmount() {
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal outdatedAmount = BigDecimal.valueOf(amount);
        BigDecimal updatedAmount = BigDecimal.valueOf(amount + 100);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(outdatedAmount);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(updatedAmount);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart,
                        dueDate
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(updatedAmount.multiply(BigDecimal.valueOf(100)), BigDecimal.valueOf(response.amount()));
    }

    @Test
    void shouldAddErrorSpanWithFaultCodeForNodoError() {
        String nodoFaultCode = "PPT_FAULT_CODE";
        instantiateNodoOperations(false);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal outdatedAmount = BigDecimal.valueOf(amount);
        BigDecimal updatedAmount = BigDecimal.valueOf(amount + 100);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(outdatedAmount);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        CtFaultBean ctFaultBean = new CtFaultBean();

        ctFaultBean.setFaultCode(nodoFaultCode);
        activatePaymentRes.setFault(ctFaultBean);
        activatePaymentRes.setOutcome(StOutcome.KO);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());
        /* test */
        StepVerifier.create(
                nodoOperations
                        .activatePaymentRequest(
                                rptId,
                                idempotencyKey,
                                amount,
                                transactionId,
                                900,
                                idCart,
                                dueDate
                        )
        )
                .expectError(NodoErrorException.class)
                .verify();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());
        Mockito.verify(openTelemetryUtils, Mockito.times(1)).addErrorSpanWithAttributes(
                OpenTelemetryUtils.NODO_ACTIVATION_ERROR_SPAN_NAME.formatted(nodoFaultCode),
                Attributes
                        .of(
                                AttributeKey.stringKey(
                                        OpenTelemetryUtils.NODO_ACTIVATION_ERROR_FAULT_CODE_ATTRIBUTE_KEY
                                ),
                                nodoFaultCode
                        )

        );

    }
}
