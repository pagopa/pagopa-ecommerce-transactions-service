package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.*;
import it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.PaymentMethodResponseDto;
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
import java.util.stream.Collectors;

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

        if (source.getPaymentNotices() != null) {
            List<it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.PaymentNoticeDto> handlerNotices = source
                    .getPaymentNotices().stream()
                    .map(this::mapPaymentNotice)
                    .collect(Collectors.toList());
            handlerRequest.setPaymentNotices(handlerNotices);
        }

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

        if (source.getTransferList() != null) {
            List<it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.TransferListItemDto> handlerTransfers = source
                    .getTransferList().stream()
                    .map(this::mapTransferListItem)
                    .collect(Collectors.toList());
            notice.setTransferList(handlerTransfers);
        }

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
        response.setPaymentMethodName(source.getPaymentMethodName());
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
                    .collect(Collectors.toList());
            response.setBundles(bundles);
        }

        return response;
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
        bundle.setPaymentMethod(source.getPaymentMethod());
        bundle.setTaxPayerFee(source.getTaxPayerFee());
        bundle.setTouchpoint(source.getTouchpoint());
        bundle.setPspBusinessName(source.getPspBusinessName());
        return bundle;
    }

    private static void logWebClientException(WebClientResponseException e) {
        log.info(
                "Got bad response from payment-methods-handler [HTTP {}]: {}",
                e.getStatusCode(),
                e.getResponseBodyAsString()
        );
    }
}
