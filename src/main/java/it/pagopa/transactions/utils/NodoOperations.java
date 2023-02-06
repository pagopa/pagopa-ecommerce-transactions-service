package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.CtQrCode;
import it.pagopa.generated.transactions.model.StOutcome;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.configurations.NodoConfig;
import it.pagopa.transactions.exceptions.InvalidNodoResponseException;
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
    NodeForPspClient nodeForPspClient;

    @Autowired
    it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;
    @Autowired
    NodoConfig nodoConfig;

    public Mono<PaymentRequestInfo> activatePaymentRequest(
                                                           RptId rptId,
                                                           Optional<PaymentRequestInfo> paymentRequestInfo,
                                                           Integer amount,
                                                           String transactionId
    ) {
        IdempotencyKey idempotencyKey = paymentRequestInfo.map(PaymentRequestInfo::idempotencyKey)
                .orElseGet(
                        () -> new IdempotencyKey(
                                PSP_PAGOPA_ECOMMERCE_FISCAL_CODE,
                                randomString(10)
                        )
                );

        final BigDecimal amountAsBigDecimal = BigDecimal.valueOf(amount.doubleValue() / 100)
                .setScale(2, RoundingMode.CEILING);

        return nodoActivationForNM3PaymentRequest(
                rptId,
                amountAsBigDecimal,
                idempotencyKey.rawValue(),
                transactionId
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
                            if (StOutcome.OK.value().equals(activatePaymentNoticeRes.getOutcome().value())) {
                                return isOkPaymentToken(activatePaymentNoticeRes.getPaymentToken())
                                        ? Mono.just(activatePaymentNoticeRes)
                                        : Mono.error(new InvalidNodoResponseException("No payment token received"));
                            } else {
                                return Mono.error(new NodoErrorException(activatePaymentNoticeRes.getFault()));
                            }
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

    private boolean isOkPaymentToken(String paymentToken) {
        return paymentToken != null && !paymentToken.isBlank();
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
