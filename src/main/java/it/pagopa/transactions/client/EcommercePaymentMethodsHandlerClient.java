package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.*;
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.PatchSessionRequestDto;
import it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.PaymentMethodResponseDto;
import it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.SessionPaymentMethodResponseDto;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.PaymentMethodNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class EcommercePaymentMethodsHandlerClient {

    private final it.pagopa.generated.ecommerce.paymentmethodshandler.v1.api.PaymentMethodsHandlerApi ecommercePaymentMethodsHandlerWebClientV1;

    @Autowired
    public EcommercePaymentMethodsHandlerClient(
            @Qualifier(
                "ecommercePaymentMethoHandlerdWebClientV1"
            ) it.pagopa.generated.ecommerce.paymentmethodshandler.v1.api.PaymentMethodsHandlerApi ecommercePaymentMethodsHandlerWebClientV1
    ) {
        this.ecommercePaymentMethodsHandlerWebClientV1 = ecommercePaymentMethodsHandlerWebClientV1;
    }

    public Mono<PaymentMethodResponseDto> getPaymentMethod(
                                                           String paymentMethodId,
                                                           String xClientId
    ) {
        // payment methods handler only support CHECKOUT_CART, CHECKOUT and IO.
        final var client = Transaction.ClientId.fromString(xClientId) == Transaction.ClientId.WISP_REDIRECT
                ? Transaction.ClientId.CHECKOUT_CART
                : Transaction.ClientId.fromString(xClientId);

        return ecommercePaymentMethodsHandlerWebClientV1.getPaymentMethod(paymentMethodId, client.name())
                .doOnNext(
                        v -> LogTracingUtils.loggerTracingUtils()
                                .dependency(LogTracingUtils.PAYMENT_METHODS_HANDLER_DEPENDENCY)
                                .success()
                                .logInfo(log, "Retrieved payment method")
                )
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsHandlerClient::logWebClientException
                )
                .onErrorMap(
                        err -> {
                            if (err instanceof WebClientResponseException.NotFound) {
                                return new PaymentMethodNotFoundException(paymentMethodId, xClientId);
                            } else {
                                return new InvalidRequestException("Error while invoke method retrieve card data");
                            }
                        }
                );
    }

    /**
     * Retrieve card data for an NPG session using the payment-methods-handler
     * service. Calls the handler's GET /payment-methods/{id}/sessions/{orderId}
     * endpoint directly, bypassing the old payment-methods-service.
     *
     * @param paymentMethodId the payment method ID
     * @param orderId         the NPG session order ID
     * @param xClientId       the client ID (IO, CHECKOUT, CHECKOUT_CART)
     * @return the session payment method (card) data
     */
    public Mono<SessionPaymentMethodResponseDto> retrieveCardData(
                                                                  String paymentMethodId,
                                                                  String orderId,
                                                                  String xClientId
    ) {
        // payment methods handler only supports CHECKOUT_CART, CHECKOUT and IO.
        final var client = Transaction.ClientId.fromString(xClientId) == Transaction.ClientId.WISP_REDIRECT
                ? Transaction.ClientId.CHECKOUT_CART
                : Transaction.ClientId.fromString(xClientId);

        return ecommercePaymentMethodsHandlerWebClientV1
                .getSessionPaymentMethod(paymentMethodId, orderId, client.name())
                .doOnNext(
                        v -> LogTracingUtils.loggerTracingUtils()
                                .dependency(LogTracingUtils.PAYMENT_METHODS_HANDLER_DEPENDENCY)
                                .success()
                                .logInfo(log, "Retrieved session payment method")
                )
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsHandlerClient::logWebClientException
                )
                .onErrorMap(
                        err -> new InvalidRequestException("Error while invoke method retrieve card data")
                );
    }

    /**
     * Associate a transaction ID to an existing NPG session using the
     * payment-methods-handler service. Calls the handler's PATCH
     * /payment-methods/{id}/sessions/{orderId} endpoint directly, bypassing the
     * old payment-methods-service.
     *
     * @param paymentMethodId the payment method ID
     * @param orderId         the NPG session order ID
     * @param transactionId   the transaction ID to associate with the session
     * @param xClientId       the client ID (IO, CHECKOUT, CHECKOUT_CART)
     * @return a completion signal
     */
    public Mono<Void> updateSession(
                                    String paymentMethodId,
                                    String orderId,
                                    String transactionId,
                                    String xClientId
    ) {
        // payment methods handler only supports CHECKOUT_CART, CHECKOUT and IO.
        final var client = Transaction.ClientId.fromString(xClientId) == Transaction.ClientId.WISP_REDIRECT
                ? Transaction.ClientId.CHECKOUT_CART
                : Transaction.ClientId.fromString(xClientId);

        return ecommercePaymentMethodsHandlerWebClientV1
                .updateSession(
                        paymentMethodId,
                        orderId,
                        client.name(),
                        new PatchSessionRequestDto().transactionId(transactionId)
                )
                .doOnSuccess(
                        v -> LogTracingUtils.loggerTracingUtils()
                                .dependency(LogTracingUtils.PAYMENT_METHODS_HANDLER_DEPENDENCY)
                                .success()
                                .logInfo(log, "Updated session payment method")
                )
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsHandlerClient::logWebClientException
                )
                .onErrorMap(
                        err -> new InvalidRequestException("Error while invoke method update session")
                );
    }

    /**
     * Calculate fees using the payment-methods-handler service. This calls the
     * handler's POST /payment-methods/{id}/fees endpoint directly, bypassing the
     * old payment-methods-service that depends on MongoDB.
     *
     * @param paymentMethodId        the payment method ID
     * @param transactionId          the transaction ID (unused by handler, kept for
     *                               interface compatibility)
     * @param calculateFeeRequestDto the fee calculation request (v2 DTO from
     *                               payment-methods-service)
     * @param maxOccurrences         max number of PSP results
     * @param xClientId              the client ID (IO, CHECKOUT, CHECKOUT_CART)
     * @param language               the user language (IT, EN, FR, DE, SL)
     * @return the fee calculation response mapped to the v2 DTO
     */
    public Mono<CalculateFeeResponseDto> calculateFee(
                                                      String paymentMethodId,
                                                      String transactionId,
                                                      CalculateFeeRequestDto calculateFeeRequestDto,
                                                      Integer maxOccurrences,
                                                      String xClientId,
                                                      String language
    ) {
        final var clientId = Transaction.ClientId.fromString(xClientId) == Transaction.ClientId.WISP_REDIRECT
                ? Transaction.ClientId.CHECKOUT_CART.name()
                : Transaction.ClientId.fromString(xClientId).name();

        // Map v2 request DTO to handler request DTO
        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeRequestDto handlerRequest = mapToHandlerFeeRequest(
                calculateFeeRequestDto
        );

        return ecommercePaymentMethodsHandlerWebClientV1.calculateFees(
                paymentMethodId,
                clientId,
                language != null ? language : "IT",
                handlerRequest,
                maxOccurrences
        )
                .map(this::mapFromHandlerFeeResponse)
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsHandlerClient::logWebClientException
                )
                .onErrorMap(
                        err -> new InvalidRequestException("Error while invoke method for read psp list from handler")
                );
    }

    /**
     * Maps the v2 CalculateFeeRequestDto to the handler's CalculateFeeRequestDto
     */
    private it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeRequestDto mapToHandlerFeeRequest(
                                                                                                                     CalculateFeeRequestDto source
    ) {
        var handlerRequest = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeRequestDto();
        handlerRequest.setTouchpoint(source.getTouchpoint());
        handlerRequest.setBin(source.getBin());
        handlerRequest.setIdPspList(source.getIdPspList());
        handlerRequest.setIsAllCCP(source.getIsAllCCP());

        List<it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.PaymentNoticeDto> handlerNotices = source
                .getPaymentNotices().stream()
                .map(this::mapPaymentNotice)
                .toList();
        handlerRequest.setPaymentNotices(handlerNotices);

        return handlerRequest;
    }

    /**
     * Maps a single PaymentNoticeDto from v2 to handler format
     */
    private it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.PaymentNoticeDto mapPaymentNotice(
                                                                                                         PaymentNoticeDto source
    ) {
        var notice = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.PaymentNoticeDto();
        notice.setPaymentAmount(source.getPaymentAmount());
        notice.setPrimaryCreditorInstitution(source.getPrimaryCreditorInstitution());

        List<it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.TransferListItemDto> handlerTransfers = source
                .getTransferList().stream()
                .map(this::mapTransferListItem)
                .toList();
        notice.setTransferList(handlerTransfers);

        return notice;
    }

    /**
     * Maps a TransferListItemDto from v2 to handler format
     */
    private it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.TransferListItemDto mapTransferListItem(
                                                                                                               TransferListItemDto source
    ) {
        var item = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.TransferListItemDto();
        item.setCreditorInstitution(source.getCreditorInstitution());
        item.setDigitalStamp(source.getDigitalStamp());
        item.setTransferCategory(source.getTransferCategory());
        return item;
    }

    /**
     * Maps the handler's CalculateFeeResponseDto to the v2 CalculateFeeResponseDto
     * expected by TransactionsService
     */
    private CalculateFeeResponseDto mapFromHandlerFeeResponse(
                                                              it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto source
    ) {
        var response = new CalculateFeeResponseDto();
        response.setPaymentMethodName(mapPaymentMethodName(source.getPaymentMethodName()));
        response.setPaymentMethodDescription(source.getPaymentMethodDescription());
        response.setBelowThreshold(source.getBelowThreshold());
        response.setAsset(source.getAsset());
        response.setBrandAssets(source.getBrandAssets());

        // Map status - handler uses ENABLED/DISABLED/MAINTENANCE, v2 uses
        // ENABLED/DISABLED/INCOMING
        if (source.getPaymentMethodStatus() != null) {
            String statusValue = source.getPaymentMethodStatus().getValue();
            try {
                response.setPaymentMethodStatus(PaymentMethodStatusDto.fromValue(statusValue));
            } catch (IllegalArgumentException e) {
                // MAINTENANCE from handler has no equivalent in v2, default to DISABLED
                log.warn("Unknown payment method status from handler: {}, defaulting to DISABLED", statusValue);
                response.setPaymentMethodStatus(PaymentMethodStatusDto.DISABLED);
            }
        }

        // Map bundles
        if (source.getBundles() != null) {
            List<BundleDto> bundles = source.getBundles().stream()
                    .map(this::mapBundle)
                    .toList();
            response.setBundles(bundles);
        }

        return response;
    }

    /**
     * Mapping from AFM payment method names to NPG PaymentMethod enum values. AFM
     * returns Italian names while NPG expects uppercase enum constants.
     */
    private static final Map<String, String> PAYMENT_METHOD_MAPPING = Map.of(
            "Carte",
            "CARDS",
            "Bancomat Pay",
            "BANCOMATPAY",
            "MyBank",
            "MYBANK",
            "PayPal",
            "PAYPAL",
            "Apple Pay",
            "APPLEPAY",
            "Satispay",
            "SATISPAY",
            "Google Pay",
            "GOOGLEPAY"
    );

    /**
     * Mapping from payment type codes to NPG PaymentMethod service names.
     */
    private static final Map<String, String> PAYMENT_TYPE_CODE_TO_NPG = Map.of(
            "CP",
            "CARDS",
            "BPAY",
            "BANCOMATPAY",
            "MYBK",
            "MYBANK",
            "PPAL",
            "PAYPAL",
            "APPL",
            "APPLEPAY",
            "SATY",
            "SATISPAY",
            "GOOG",
            "GOOGLEPAY"
    );

    /**
     * Maps a payment type code (e.g. "CP") to the NPG PaymentMethod service name
     * (e.g. "CARDS"). Falls back to the original value if no mapping is found.
     */
    public static String mapPaymentTypeCodeToNpgServiceName(String paymentTypeCode) {
        if (paymentTypeCode == null) {
            return null;
        }
        return PAYMENT_TYPE_CODE_TO_NPG.getOrDefault(paymentTypeCode, paymentTypeCode);
    }

    /**
     * Maps a single Bundle from handler format to v2 format
     */
    private BundleDto mapBundle(
                                it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.BundleDto source
    ) {
        var bundle = new BundleDto();
        bundle.setAbi(source.getAbi());
        bundle.setBundleDescription(source.getBundleDescription());
        bundle.setBundleName(source.getPspBusinessName());
        bundle.setIdBrokerPsp(source.getIdBrokerPsp());
        bundle.setIdBundle(source.getIdBundle());
        bundle.setIdChannel(source.getIdChannel());
        bundle.setIdPsp(source.getIdPsp());
        bundle.setOnUs(source.getOnUs());
        bundle.setPaymentMethod(mapPaymentMethodName(source.getPaymentMethod()));
        bundle.setTaxPayerFee(source.getTaxPayerFee());
        bundle.setTouchpoint(source.getTouchpoint());
        bundle.setPspBusinessName(source.getPspBusinessName());
        return bundle;
    }

    /**
     * Maps AFM payment method name to NPG PaymentMethod enum value. Falls back to
     * the original value if no mapping is found.
     */
    private String mapPaymentMethodName(String afmPaymentMethod) {
        if (afmPaymentMethod == null) {
            return null;
        }
        return PAYMENT_METHOD_MAPPING.getOrDefault(afmPaymentMethod, afmPaymentMethod);
    }

    private static void logWebClientException(WebClientResponseException e) {
        LogTracingUtils.loggerTracingUtils()
                .dependency(LogTracingUtils.PAYMENT_METHODS_HANDLER_DEPENDENCY)
                .failure()
                .details(
                        Map.of(
                                "status_code",
                                Objects.toString(e.getStatusCode()),
                                "response_body",
                                e.getResponseBodyAsString()
                        )
                )
                .logError(log, e, "Got bad response from payment-methods-handler");
    }
}
