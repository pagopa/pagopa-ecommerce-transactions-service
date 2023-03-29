package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.generated.transactions.model.*;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.configurations.NodoConfig;
import it.pagopa.transactions.exceptions.InvalidNodoResponseException;
import it.pagopa.transactions.exceptions.NodoErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Optional;

@Slf4j
@Component
public class NodoOperations {

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
                                                           IdempotencyKey idempotencyKey,
                                                           Integer amount,
                                                           String transactionId,
                                                           Integer paymentTokenTimeout
    ) {

        final BigDecimal amountAsBigDecimal = BigDecimal.valueOf(amount.doubleValue() / 100)
                .setScale(2, RoundingMode.CEILING);

        return nodoActivationForNM3PaymentRequest(
                rptId,
                amountAsBigDecimal,
                idempotencyKey.rawValue(),
                transactionId,
                paymentTokenTimeout
        );
    }

    private Mono<PaymentRequestInfo> nodoActivationForNM3PaymentRequest(
                                                                        RptId rptId,
                                                                        BigDecimal amount,
                                                                        String idempotencyKey,
                                                                        String transactionId,
                                                                        Integer paymentTokenTimeout
    ) {
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(rptId.getFiscalCode());
        qrCode.setNoticeNumber(rptId.getNoticeId());
        ActivatePaymentNoticeV2Request request = nodoConfig.baseActivatePaymentNoticeV2Request();
        request.setAmount(amount);
        request.setQrCode(qrCode);
        request.setIdempotencyKey(idempotencyKey);
        // multiply paymentTokenTimeout by 1000 because on ecommerce it is represented
        // in seconds
        request.setExpirationTime(BigInteger.valueOf(paymentTokenTimeout).multiply(BigInteger.valueOf(1000)));
        // TODO Maybe here more values (all optional) can be passed such as Touchpoint,
        // Payment Method Type Code, Due date
        // request.setPaymentMethod();
        // request.setTouchPoint(StTouchpointFee.CHECKOUT);
        return nodeForPspClient
                .activatePaymentNoticeV2(objectFactoryNodeForPsp.createActivatePaymentNoticeV2Request(request))
                .flatMap(
                        activatePaymentNoticeV2Response -> {
                            log.info(
                                    "Nodo activation for NM3 payment. Transaction id: [{}] RPT id: [{}] response outcome: [{}]",
                                    transactionId,
                                    rptId,
                                    activatePaymentNoticeV2Response.getOutcome()
                            );
                            if (StOutcome.OK.value().equals(activatePaymentNoticeV2Response.getOutcome().value())) {
                                return isOkPaymentToken(activatePaymentNoticeV2Response.getPaymentToken())
                                        ? Mono.just(activatePaymentNoticeV2Response)
                                        : Mono.error(new InvalidNodoResponseException("No payment token received"));
                            } else {
                                return Mono.error(new NodoErrorException(activatePaymentNoticeV2Response.getFault()));
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
                                response.getPaymentToken(),
                                new IdempotencyKey(idempotencyKey),
                                new ArrayList<>() // TODO TRANSFER LIST
                        )
                );
    }

    private boolean isOkPaymentToken(String paymentToken) {
        return paymentToken != null && !paymentToken.isBlank();
    }

    public Integer getEuroCentsFromNodoAmount(BigDecimal amountFromNodo) {
        return amountFromNodo.multiply(BigDecimal.valueOf(100)).intValue();
    }

    public String generateRandomStringToIdempotencyKey() {
        int len = 10;
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHANUMERICS.charAt(RANDOM.nextInt(ALPHANUMERICS.length())));
        }
        return sb.toString();
    }

    public String getEcommerceFiscalCode() {
        return nodoConfig.nodoConnectionString().getIdBrokerPSP();
    }
}
