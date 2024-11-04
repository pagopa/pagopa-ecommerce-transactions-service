package it.pagopa.transactions.utils;

import io.opentelemetry.api.common.Attributes;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.PaymentTransferInfo;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.utils.EuroUtils;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.generated.transactions.model.*;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.configurations.NodoConfig;
import it.pagopa.transactions.exceptions.InvalidNodoResponseException;
import it.pagopa.transactions.exceptions.NodoErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@Component
public class NodoOperations {

    private static final String ALPHANUMERICS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String IBANAPPOGGIO = "IBANAPPOGGIO";
    private static final String CONVENTION_TRANSFER_FISCAL_CODE = "66666666666";

    NodeForPspClient nodeForPspClient;

    it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

    NodoConfig nodoConfig;

    private boolean allCCPOnTransferIbanEnabled;

    private final OpenTelemetryUtils openTelemetryUtils;

    @Autowired
    public NodoOperations(
            NodeForPspClient nodeForPspClient,
            ObjectFactory objectFactoryNodeForPsp,
            NodoConfig nodoConfig,
            @Value("${nodo.allCCPOnTransferIbanEnabled}") boolean allCCPOnTransferIbanEnabled,
            OpenTelemetryUtils openTelemetryUtils
    ) {
        this.nodeForPspClient = nodeForPspClient;
        this.objectFactoryNodeForPsp = objectFactoryNodeForPsp;
        this.nodoConfig = nodoConfig;
        this.allCCPOnTransferIbanEnabled = allCCPOnTransferIbanEnabled;
        this.openTelemetryUtils = openTelemetryUtils;
    }

    public Mono<PaymentRequestInfo> activatePaymentRequest(
                                                           RptId rptId,
                                                           IdempotencyKey idempotencyKey,
                                                           Integer amount,
                                                           String transactionId,
                                                           Integer paymentTokenTimeout,
                                                           String idCart,
                                                           String dueDate,
                                                           Transaction.ClientId clientId
    ) {
        return activatePaymentRequest(
                rptId,
                idempotencyKey,
                amount,
                transactionId,
                paymentTokenTimeout,
                idCart,
                dueDate
        )
                .flatMap(paymentRequestInfo -> {
                    if (clientId == Transaction.ClientId.WISP_REDIRECT
                            && paymentRequestInfo.creditorReferenceId() == null) {
                        return Mono.error(
                                new InvalidNodoResponseException(
                                        String.format(
                                                "Mandatory creditorReferenceId for client %s is missing",
                                                clientId.name()
                                        )
                                )
                        );
                    } else {
                        return Mono.just(paymentRequestInfo);
                    }
                });
    }

    private Mono<PaymentRequestInfo> activatePaymentRequest(
                                                            RptId rptId,
                                                            IdempotencyKey idempotencyKey,
                                                            Integer amount,
                                                            String transactionId,
                                                            Integer paymentTokenTimeout,
                                                            String idCart,
                                                            String dueDate
    ) {

        final BigDecimal amountAsBigDecimal = BigDecimal.valueOf(amount.doubleValue() / 100)
                .setScale(2, RoundingMode.CEILING);

        return nodoActivationForNM3PaymentRequest(
                rptId,
                amountAsBigDecimal,
                idempotencyKey.rawValue(),
                transactionId,
                paymentTokenTimeout,
                idCart,
                dueDate
        );
    }

    private Mono<PaymentRequestInfo> nodoActivationForNM3PaymentRequest(
                                                                        RptId rptId,
                                                                        BigDecimal amount,
                                                                        String idempotencyKey,
                                                                        String transactionId,
                                                                        Integer paymentTokenTimeout,
                                                                        String idCart,
                                                                        String dueDate
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
        //
        // TODO Maybe here more values (all optional) can be passed such as Touchpoint
        // and PaymentMethod
        return nodeForPspClient
                .activatePaymentNoticeV2(objectFactoryNodeForPsp.createActivatePaymentNoticeV2Request(request))
                .flatMap(
                        activatePaymentNoticeV2Response -> {
                            String faultCode = Optional.ofNullable(activatePaymentNoticeV2Response.getFault())
                                    .map(CtFaultBean::getFaultCode)
                                    .orElse("No faultCode received");
                            log.info(
                                    "Nodo activation for  transaction id: [{}] RPT id: [{}] idCart: [{}] response outcome: [{}] faultCode: [{}]",
                                    transactionId,
                                    rptId,
                                    Optional.ofNullable(idCart).orElse("idCart not present"),
                                    activatePaymentNoticeV2Response.getOutcome(),
                                    faultCode
                            );
                            if (StOutcome.OK.value().equals(activatePaymentNoticeV2Response.getOutcome().value())) {
                                openTelemetryUtils.addSpanWithAttributes(
                                        SpanLabelOpenTelemetry.NODO_ACTIVATION_OK_SPAN_NAME,
                                        Attributes.of(
                                                SpanLabelOpenTelemetry.NODO_ACTIVATION_ERROR_FAULT_CODE_ATTRIBUTE_KEY,
                                                StOutcome.OK.toString()
                                        )
                                );
                                return isOkPaymentToken(activatePaymentNoticeV2Response.getPaymentToken())
                                        ? Mono.just(activatePaymentNoticeV2Response)
                                        : Mono.error(new InvalidNodoResponseException("No payment token received"));
                            } else {
                                openTelemetryUtils.addErrorSpanWithAttributes(
                                        SpanLabelOpenTelemetry.NODO_ACTIVATION_ERROR_SPAN_NAME.formatted(faultCode),
                                        Attributes.of(
                                                SpanLabelOpenTelemetry.NODO_ACTIVATION_ERROR_FAULT_CODE_ATTRIBUTE_KEY,
                                                faultCode
                                        )
                                );
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
                                dueDate,
                                response.getPaymentToken(),
                                ZonedDateTime.now().toString(),
                                new IdempotencyKey(idempotencyKey),
                                getPaymentTransferInfoList(
                                        response.getTransferList().getTransfer(),
                                        response.getMetadata()
                                ),
                                isAllCCP(response, allCCPOnTransferIbanEnabled),
                                response.getCreditorReferenceId()
                        )
                );
    }

    private boolean isAllCCP(
                             ActivatePaymentNoticeV2Response response,
                             boolean allCCPOnTransferIbanEnabled
    ) {
        return allCCPOnTransferIbanEnabled
                ? response.getTransferList().getTransfer().parallelStream().allMatch(t -> isIbanCCP(t.getIBAN()))
                : response.getTransferList().getTransfer().parallelStream().allMatch(
                        ctTransferPSPV2 -> ctTransferPSPV2.getMetadata() != null &&
                                ctTransferPSPV2.getMetadata().getMapEntry().parallelStream()
                                        .allMatch(
                                                ctMapEntry -> ctMapEntry.getKey()
                                                        .equals(IBANAPPOGGIO)
                                        )
                                &&
                                (isIbanCCP(ctTransferPSPV2.getIBAN())
                                        ||
                                        ctTransferPSPV2.getMetadata().getMapEntry()
                                                .parallelStream()
                                                .anyMatch(
                                                        ctMapEntry -> ctMapEntry
                                                                .getKey()
                                                                .equals(IBANAPPOGGIO)
                                                                && isIbanCCP(ctMapEntry.getValue())
                                                ))
                );
    }

    private boolean isOkPaymentToken(String paymentToken) {
        return paymentToken != null && !paymentToken.isBlank();
    }

    private boolean isIbanCCP(String iban) {
        return iban != null
                && iban.length() > 10
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

    /**
     * Retrieves a list of payment transfer information based on the provided
     * transfer data and metadata.
     *
     * <p>
     * This method processes a list of transfer objects and associated metadata to
     * generate a corresponding list of payment transfer information. If the
     * metadata contains a convention specifying additional transfers, these will be
     * included in the resulting list (for more details see CHK-3525).
     * </p>
     *
     * @param transferPSPV2List a list of {@link CtTransferPSPV2} objects containing
     *                          the payment transfer data to be processed.
     * @param metadata          an instance of {@link CtMetadata} containing
     *                          relevant metadata for processing the transfers,
     *                          which may include conventions for adding additional
     *                          transfers.
     * @return a list of {@link PaymentTransferInfo} objects, each representing
     *         detailed information about a payment transfer, including any
     *         additional transfers specified by the metadata.
     *
     * @throws IllegalArgumentException if the input parameters are null or invalid.
     */
    private List<PaymentTransferInfo> getPaymentTransferInfoList(
                                                                 List<CtTransferPSPV2> transferPSPV2List,
                                                                 CtMetadata metadata
    ) {
        List<PaymentTransferInfo> baseTransferList = transferPSPV2List.stream()
                .map(
                        transfer -> new PaymentTransferInfo(
                                transfer.getFiscalCodePA(),
                                transfer.getRichiestaMarcaDaBollo() != null,
                                EuroUtils.euroToEuroCents(transfer.getTransferAmount()),
                                transfer.getTransferCategory()
                        )
                )
                .toList();

        Optional<String> conventionValue = Optional.ofNullable(metadata)
                .map(CtMetadata::getMapEntry)
                .orElse(List.of())
                .stream()
                .filter(entry -> "codiceConvenzione".equals(entry.getKey()))
                .map(CtMapEntry::getValue)
                .findFirst();

        return (conventionValue.isPresent())
                ? Stream.concat(
                        baseTransferList.stream(),
                        Stream.of(
                                new PaymentTransferInfo(
                                        CONVENTION_TRANSFER_FISCAL_CODE,
                                        false,
                                        0,
                                        conventionValue
                                )
                        )
                )
                        .toList()
                : baseTransferList;
    }
}
