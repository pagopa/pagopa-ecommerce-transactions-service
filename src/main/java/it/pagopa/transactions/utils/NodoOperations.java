package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.v1.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.v1.PaymentTransferInfo;
import it.pagopa.ecommerce.commons.domain.v1.RptId;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.utils.EuroUtils;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeV2Request;
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
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.Optional;

@Slf4j
@Component
public class NodoOperations {

    private static final String ALPHANUMERICS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IBAN_LENGTH = 27;

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
                                                           Integer paymentTokenTimeout,
                                                           String idCart
    ) {

        final BigDecimal amountAsBigDecimal = BigDecimal.valueOf(amount.doubleValue() / 100)
                .setScale(2, RoundingMode.CEILING);

        return nodoActivationForNM3PaymentRequest(
                rptId,
                amountAsBigDecimal,
                idempotencyKey.rawValue(),
                transactionId,
                paymentTokenTimeout,
                idCart
        );
    }

    private Mono<PaymentRequestInfo> nodoActivationForNM3PaymentRequest(
                                                                        RptId rptId,
                                                                        BigDecimal amount,
                                                                        String idempotencyKey,
                                                                        String transactionId,
                                                                        Integer paymentTokenTimeout,
                                                                        String idCart
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
        request.setPaymentNote(idCart);
        // TODO Maybe here more values (all optional) can be passed such as Touchpoint
        // and PaymentMethod
        return nodeForPspClient
                .activatePaymentNoticeV2(objectFactoryNodeForPsp.createActivatePaymentNoticeV2Request(request))
                .flatMap(
                        activatePaymentNoticeV2Response -> {
                            log.info(
                                    "Nodo activation for NM3 payment. Transaction id: [{}] RPT id: [{}] idCart: [{}] response outcome: [{}]",
                                    transactionId,
                                    rptId,
                                    Optional.ofNullable(idCart).orElse("idCart not present"),
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
                                getEuroCentsFromNodoAmount(response.getTotalAmount()),
                                null,
                                response.getPaymentToken(),
                                ZonedDateTime.now().toString(),
                                new IdempotencyKey(idempotencyKey),
                                response.getTransferList().getTransfer().stream()
                                        .map(
                                                transfer -> new PaymentTransferInfo(
                                                        transfer.getFiscalCodePA(),
                                                        transfer.getRichiestaMarcaDaBollo() != null,
                                                        EuroUtils.euroToEuroCents(transfer.getTransferAmount()),
                                                        transfer.getTransferCategory()
                                                )
                                        ).toList(),
                                response.getTransferList().getTransfer().parallelStream().allMatch(
                                        ctTransferPSPV2 -> ctTransferPSPV2.getMetadata() != null &&
                                                ctTransferPSPV2.getMetadata().getMapEntry().parallelStream()
                                                        .allMatch(
                                                                ctMapEntry -> ctMapEntry.getKey()
                                                                        .equals("IBANAPPOGGIO")
                                                        )
                                                &&
                                                (isIbanCCP(ctTransferPSPV2.getIBAN())
                                                        ||
                                                        ctTransferPSPV2.getMetadata().getMapEntry()
                                                                .parallelStream()
                                                                .anyMatch(
                                                                        ctMapEntry -> ctMapEntry
                                                                                .getKey()
                                                                                .equals("IBANAPPOGGIO")
                                                                                && isIbanCCP(ctMapEntry.getValue())
                                                                ))
                                )
                        )
                );
    }

    private boolean isOkPaymentToken(String paymentToken) {
        return paymentToken != null && !paymentToken.isBlank();
    }

    private boolean isIbanCCP(String iban) {
        return iban != null
                && iban.length() == IBAN_LENGTH
                && iban.substring(5, 10).equalsIgnoreCase("07601");

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
