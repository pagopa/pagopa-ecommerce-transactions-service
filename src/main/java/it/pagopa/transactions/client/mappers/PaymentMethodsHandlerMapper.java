package it.pagopa.transactions.client.mappers;

import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.BundleDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeResponseDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentMethodStatusDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentNoticeDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.TransferListItemDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class PaymentMethodsHandlerMapper {

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

    private static final Map<String, String> NPG_SERVICE_NAME_TO_PAYMENT_TYPE_CODE = PAYMENT_TYPE_CODE_TO_NPG
            .entrySet()
            .stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    public it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeRequestDto toHandlerFeeRequest(
                                                                                                                 CalculateFeeRequestDto source
    ) {
        var handlerRequest = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeRequestDto();
        handlerRequest.setTouchpoint(source.getTouchpoint());
        handlerRequest.setBin(source.getBin());
        handlerRequest.setIdPspList(source.getIdPspList());
        handlerRequest.setIsAllCCP(source.getIsAllCCP());

        handlerRequest.setPaymentNotices(
                source.getPaymentNotices().stream()
                        .map(this::mapPaymentNotice)
                        .toList()
        );
        return handlerRequest;
    }

    public CalculateFeeResponseDto fromHandlerFeeResponse(
                                                          it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto source
    ) {
        var response = new CalculateFeeResponseDto();
        String paymentMethodName = source.getPaymentTypeCode() != null
                ? mapPaymentTypeCodeToNpgServiceName(source.getPaymentTypeCode())
                : mapPaymentMethodName(source.getPaymentMethodName());

        response.setPaymentMethodName(paymentMethodName);
        response.setPaymentMethodDescription(source.getPaymentMethodDescription());
        response.setBelowThreshold(source.getBelowThreshold());
        response.setAsset(source.getAsset());
        response.setBrandAssets(source.getBrandAssets());

        if (source.getPaymentMethodStatus() != null) {
            String statusValue = source.getPaymentMethodStatus().getValue();
            try {
                response.setPaymentMethodStatus(PaymentMethodStatusDto.fromValue(statusValue));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown payment method status from handler: {}, defaulting to DISABLED", statusValue);
                response.setPaymentMethodStatus(PaymentMethodStatusDto.DISABLED);
            }
        }

        if (source.getBundles() != null) {
            List<BundleDto> bundles = source.getBundles().stream()
                    .map(this::mapBundle)
                    .toList();
            response.setBundles(bundles);
        }

        return response;
    }

    public static String mapPaymentTypeCodeToNpgServiceName(String paymentTypeCode) {
        if (paymentTypeCode == null) {
            return null;
        }
        return PAYMENT_TYPE_CODE_TO_NPG.getOrDefault(paymentTypeCode, paymentTypeCode);
    }

    private it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.PaymentNoticeDto mapPaymentNotice(
                                                                                                         PaymentNoticeDto source
    ) {
        var notice = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.PaymentNoticeDto();
        notice.setPaymentAmount(source.getPaymentAmount());
        notice.setPrimaryCreditorInstitution(source.getPrimaryCreditorInstitution());

        notice.setTransferList(
                source.getTransferList().stream()
                        .map(this::mapTransferListItem)
                        .toList()
        );
        return notice;
    }

    private it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.TransferListItemDto mapTransferListItem(
                                                                                                               TransferListItemDto source
    ) {
        var item = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.TransferListItemDto();
        item.setCreditorInstitution(source.getCreditorInstitution());
        item.setDigitalStamp(source.getDigitalStamp());
        item.setTransferCategory(source.getTransferCategory());
        return item;
    }

    private BundleDto mapBundle(
                                it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.BundleDto source
    ) {
        var bundle = new BundleDto();
        bundle.setAbi(source.getAbi());
        bundle.setBundleDescription(source.getBundleDescription());
        bundle.setBundleName(null); // missing bundleName in BundleDto source
        bundle.setIdBrokerPsp(source.getIdBrokerPsp());
        bundle.setIdBundle(source.getIdBundle());
        bundle.setIdChannel(source.getIdChannel());
        bundle.setIdPsp(source.getIdPsp());
        bundle.setOnUs(source.getOnUs());
        bundle.setPaymentMethod(mapBundlePaymentMethodToPaymentTypeCode(source.getPaymentMethod()));
        bundle.setTaxPayerFee(source.getTaxPayerFee());
        bundle.setTouchpoint(source.getTouchpoint());
        bundle.setPspBusinessName(source.getPspBusinessName());
        return bundle;
    }

    /**
     * Converts the AFM payment method name returned by the handler (e.g., "Carte")
     * into the corresponding payment type code expected by downstream services
     * (e.g., "CP").
     * <p>
     * Downstream authorization flows require standardized type codes rather than
     * localized AFM names or NPG service names. This method resolves the code via a
     * two-step lookup (AFM name -> NPG service name -> payment type code) and falls
     * back to the original value if no mapping is found.
     *
     * @param afmPaymentMethod the payment method name from AFM
     * @return the corresponding payment type code, or the original value as
     *         fallback
     */
    private String mapBundlePaymentMethodToPaymentTypeCode(String afmPaymentMethod) {
        if (afmPaymentMethod == null) {
            return null;
        }
        String npgServiceName = mapPaymentMethodName(afmPaymentMethod);
        return NPG_SERVICE_NAME_TO_PAYMENT_TYPE_CODE.getOrDefault(npgServiceName, afmPaymentMethod);
    }

    private String mapPaymentMethodName(String afmPaymentMethod) {
        if (afmPaymentMethod == null) {
            return null;
        }
        return PAYMENT_METHOD_MAPPING.getOrDefault(afmPaymentMethod, afmPaymentMethod);
    }
}
