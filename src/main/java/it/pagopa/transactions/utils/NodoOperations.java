package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.generated.nodoperpsp.model.*;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.CtQrCode;
import it.pagopa.generated.transactions.model.StOutcome;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NodoPerPspClient;
import it.pagopa.transactions.configurations.NodoConfig;
import it.pagopa.transactions.exceptions.NodoErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.Optional;

@Slf4j
@Component
public class NodoOperations {

    private static final String PSP_PAGOPA_ECOMMERCE_FISCAL_CODE = "00000000000";

    private static final String ALPHANUMERICS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    NodoPerPspClient nodoPerPspClient;

    @Autowired
    NodeForPspClient nodeForPspClient;

    @Autowired
    it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp;

    @Autowired
    it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;
    @Autowired
    NodoConfig nodoConfig;

    @Autowired
    NodoUtilities nodoUtilities;

    public Mono<PaymentRequestInfo> activatePaymentRequest(
                                                           RptId rptId,
                                                           Optional<PaymentRequestInfo> paymentRequestInfo,
                                                           String paymentContextCode,
                                                           Integer amount,
                                                           boolean multiplePaymentNotices,
                                                           String transactionId
    ) {
        boolean isNM3 = paymentRequestInfo.map(PaymentRequestInfo::isNM3).orElse(false);
        IdempotencyKey idempotencyKey = paymentRequestInfo.map(PaymentRequestInfo::idempotencyKey)
                .orElseGet(
                        () -> new IdempotencyKey(
                                PSP_PAGOPA_ECOMMERCE_FISCAL_CODE,
                                randomString(10)
                        )
                );

        final BigDecimal amountAsBigDecimal = BigDecimal.valueOf(amount.doubleValue() / 100)
                .setScale(2, RoundingMode.CEILING);

        return Mono.just(isNM3)
                .flatMap(
                        validIsNM3 -> validIsNM3 || multiplePaymentNotices
                                ? nodoActivationForNM3PaymentRequest(
                                        rptId,
                                        amountAsBigDecimal,
                                        idempotencyKey.rawValue(),
                                        transactionId
                                )
                                : nodoActivationForUnknownPaymentRequest(
                                        rptId,
                                        amountAsBigDecimal,
                                        idempotencyKey.rawValue(),
                                        paymentContextCode,
                                        transactionId
                                )
                );
    }

    private Mono<PaymentRequestInfo> nodoActivationForNM3PaymentRequest(
                                                                        RptId rptId,
                                                                        BigDecimal amount,
                                                                        String idempotencyKey,
                                                                        String transactionId
    ) {
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(rptId.getFiscalCode());
        qrCode.setNoticeNumber(rptId.getNoticeId());
        ActivatePaymentNoticeReq request = nodoConfig.baseActivatePaymentNoticeReq();
        request.setAmount(amount);
        request.setQrCode(qrCode);
        request.setIdempotencyKey(idempotencyKey);

        return nodeForPspClient
                .activatePaymentNotice(objectFactoryNodeForPsp.createActivatePaymentNoticeReq(request))
                .flatMap(
                        activatePaymentNoticeRes -> {
                            log.info(
                                    "Nodo activation for NM3 payment. Transaction id: [{}] RPT id: [{}] response outcome: [{}]",
                                    transactionId,
                                    rptId,
                                    activatePaymentNoticeRes.getOutcome()
                            );
                            return StOutcome.OK.value()
                                    .equals(activatePaymentNoticeRes.getOutcome().value())
                                            ? Mono.just(activatePaymentNoticeRes)
                                            : Mono.error(
                                                    new NodoErrorException(activatePaymentNoticeRes.getFault())
                                            );
                        }
                )
                .map(
                        response -> new PaymentRequestInfo(
                                rptId,
                                response.getFiscalCodePA(),
                                response.getCompanyName(),
                                response.getPaymentDescription(),
                                amount.multiply(BigDecimal.valueOf(100)).intValue(),
                                null,
                                true,
                                response.getPaymentToken(),
                                new IdempotencyKey(idempotencyKey)
                        )
                );
    }

    private Mono<PaymentRequestInfo> nodoActivationForUnknownPaymentRequest(
                                                                            RptId rptId,
                                                                            BigDecimal amount,
                                                                            String idempotencyKey,
                                                                            String paymentContextCode,
                                                                            String transactionId
    ) {
        NodoAttivaRPT nodoAttivaRPTReq = nodoConfig.baseNodoAttivaRPTRequest();
        NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = nodoUtilities.getCodiceIdRpt(rptId);
        NodoTipoDatiPagamentoPSP datiPagamentoPsp = objectFactoryNodoPerPsp.createNodoTipoDatiPagamentoPSP();
        datiPagamentoPsp.setImportoSingoloVersamento(amount);
        nodoAttivaRPTReq.setDatiPagamentoPSP(datiPagamentoPsp);
        nodoAttivaRPTReq.setCodiceIdRPT(nodoTipoCodiceIdRPT);
        nodoAttivaRPTReq.setCodiceContestoPagamento(paymentContextCode);
        return nodoPerPspClient
                .attivaRPT(objectFactoryNodoPerPsp.createNodoAttivaRPT(nodoAttivaRPTReq))
                .flatMap(
                        nodoAttivaRPTResponse -> {
                            final EsitoNodoAttivaRPTRisposta nodoAttivaRPTRResponse = nodoAttivaRPTResponse
                                    .getNodoAttivaRPTRisposta();
                            final String outcome = nodoAttivaRPTRResponse.getEsito();
                            final Boolean isNM3GivenAttivaRPTRisposta = StOutcome.KO.value().equals(outcome)
                                    && "PPT_MULTI_BENEFICIARIO"
                                            .equals(nodoAttivaRPTRResponse.getFault().getFaultCode());

                            log.info(
                                    "Esito nodo attiva RPT. Transaction id: [{}] RPT id: [{}] outcome: [{}], is multibeneficiary response code: [{}]",
                                    transactionId,
                                    rptId,
                                    outcome,
                                    isNM3GivenAttivaRPTRisposta
                            );
                            if (Boolean.TRUE.equals(isNM3GivenAttivaRPTRisposta)) {
                                return nodoActivationForNM3PaymentRequest(
                                        rptId,
                                        amount,
                                        idempotencyKey,
                                        transactionId
                                );
                            }

                            final NodoTipoDatiPagamentoPA datiPagamentoPA = nodoAttivaRPTResponse
                                    .getNodoAttivaRPTRisposta()
                                    .getDatiPagamentoPA();
                            final String paName = Optional.ofNullable(datiPagamentoPA.getEnteBeneficiario())
                                    .map(CtEnteBeneficiario::getDenominazioneBeneficiario)
                                    .orElse(null);

                            final String description = datiPagamentoPA.getCausaleVersamento();

                            return StOutcome.OK.value().equals(nodoAttivaRPTRResponse.getEsito())
                                    ? Mono.just(
                                            new PaymentRequestInfo(
                                                    rptId,
                                                    rptId.getFiscalCode(),
                                                    paName,
                                                    description,
                                                    amount.multiply(BigDecimal.valueOf(100)).intValue(),
                                                    null,
                                                    false,
                                                    paymentContextCode,
                                                    new IdempotencyKey(idempotencyKey)

                                            )
                                    )
                                    : Mono.error(
                                            new NodoErrorException(nodoAttivaRPTRResponse.getFault())
                                    );
                        }
                );
    }

    public Integer getEuroCentsFromNodoAmount(BigDecimal amountFromNodo) {
        return amountFromNodo.multiply(BigDecimal.valueOf(100)).intValue();
    }

    private String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHANUMERICS.charAt(RANDOM.nextInt(ALPHANUMERICS.length())));
        }
        return sb.toString();
    }
}
