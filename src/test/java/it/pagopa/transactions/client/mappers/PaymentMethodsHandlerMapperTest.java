package it.pagopa.transactions.client.mappers;

import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeResponseDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentNoticeDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.TransferListItemDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMethodsHandlerMapperTest {

    private final PaymentMethodsHandlerMapper mapper = new PaymentMethodsHandlerMapper();

    @Test
    void shouldMapPaymentNoticesCorrectly() {
        CalculateFeeRequestDto feeRequest = new CalculateFeeRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeDto()
                                .paymentAmount(1500L)
                                .primaryCreditorInstitution("12345678901")
                                .addTransferListItem(
                                        new TransferListItemDto()
                                                .creditorInstitution("12345678901")
                                                .digitalStamp(true)
                                                .transferCategory("TAX")
                                )
                )
                .bin("654321")
                .touchpoint("IO")
                .idPspList(List.of("psp1", "psp2"))
                .isAllCCP(true);

        /* test */
        var capturedRequest = mapper.toHandlerFeeRequest(feeRequest);

        /* asserts */
        assertThat(capturedRequest.getTouchpoint()).isEqualTo("IO");
        assertThat(capturedRequest.getBin()).isEqualTo("654321");
        assertThat(capturedRequest.getIdPspList()).containsExactly("psp1", "psp2");
        assertThat(capturedRequest.getIsAllCCP()).isTrue();
        assertThat(capturedRequest.getPaymentNotices()).hasSize(1);

        var notice = capturedRequest.getPaymentNotices().get(0);
        assertThat(notice.getPaymentAmount()).isEqualTo(1500L);
        assertThat(notice.getPrimaryCreditorInstitution()).isEqualTo("12345678901");
        assertThat(notice.getTransferList()).hasSize(1);
        assertThat(notice.getTransferList().get(0).getCreditorInstitution()).isEqualTo("12345678901");
        assertThat(notice.getTransferList().get(0).getDigitalStamp()).isTrue();
        assertThat(notice.getTransferList().get(0).getTransferCategory()).isEqualTo("TAX");
    }

    @Test
    void shouldMapFromHandlerFeeResponse() {
        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("Carte")
                .paymentMethodDescription("Carte di credito")
                .paymentTypeCode("CP")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto.PaymentMethodStatusEnum.ENABLED
                )
                .belowThreshold(false)
                .asset("asset-url")
                .bundles(
                        List.of(
                                new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.BundleDto()
                                        .abi("abiTest")
                                        .bundleDescription("descriptionTest")
                                        .pspBusinessName("pspBusinessNameTest")
                                        .idBrokerPsp("idBrokerPspTest")
                                        .idBundle("idBundleTest")
                                        .idChannel("idChannelTest")
                                        .idPsp("pspId")
                                        .onUs(false)
                                        .paymentMethod("CP")
                                        .taxPayerFee(100L)
                                        .touchpoint("CHECKOUT")
                        )
                );

        /* test */
        CalculateFeeResponseDto result = mapper.fromHandlerFeeResponse(handlerResponse);

        /* asserts */
        assertThat(result).isNotNull();
        // paymentMethodName is derived from the AFM payment type code "CP" -> "CARDS",
        // not from the localized paymentMethodName
        assertThat(result.getPaymentMethodName()).isEqualTo("CARDS");
        assertThat(result.getPaymentMethodDescription()).isEqualTo("Carte di credito");
        assertThat(result.getAsset()).isEqualTo("asset-url");
        assertThat(result.getBundles()).hasSize(1);
        assertThat(result.getBundles().get(0).getIdPsp()).isEqualTo("pspId");
        assertThat(result.getBundles().get(0).getTaxPayerFee()).isEqualTo(100L);
        assertThat(result.getBundles().get(0).getPspBusinessName()).isEqualTo("pspBusinessNameTest");
        assertThat(result.getBundles().get(0).getBundleName()).isNull();
    }

    @Test
    void shouldFallBackToRawTypeCodeForApmNotInNpgMapping() {
        // RBPR (Conto BancoPosta) is an APM redirect type code not present in the NPG
        // enum:
        // mapPaymentTypeCodeToNpgServiceName must fall back to the raw code rather than
        // failing.
        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("Conto BancoPosta")
                .paymentMethodDescription("Conto BancoPosta")
                .paymentTypeCode("RBPR")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto.PaymentMethodStatusEnum.ENABLED
                )
                .belowThreshold(false)
                .asset("asset-url")
                .bundles(
                        List.of(
                                new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.BundleDto()
                                        .idPsp("BPPIITRRXXX")
                                        .onUs(false)
                                        .paymentMethod("RBPR")
                                        .taxPayerFee(150L)
                                        .touchpoint("CHECKOUT")
                        )
                );

        /* test */
        CalculateFeeResponseDto result = mapper.fromHandlerFeeResponse(handlerResponse);

        /* asserts */
        assertThat(result).isNotNull();
        assertThat(result.getPaymentMethodName()).isEqualTo("RBPR");
        assertThat(result.getBundles().get(0).getPaymentMethod()).isEqualTo("RBPR");
    }

    @Test
    void shouldFallBackToLocalizedNameMappingWhenTypeCodeMissing() {
        // No paymentTypeCode from the handler: fall back to mapping the localized name.
        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("Carte")
                .paymentMethodDescription("Carte di credito")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto.PaymentMethodStatusEnum.ENABLED
                )
                .belowThreshold(false)
                .asset("asset-url")
                .bundles(
                        List.of(
                                new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.BundleDto()
                                        .idPsp("pspId")
                                        .onUs(false)
                                        .paymentMethod("CP")
                                        .taxPayerFee(100L)
                                        .touchpoint("CHECKOUT")
                        )
                );

        /* test */
        CalculateFeeResponseDto result = mapper.fromHandlerFeeResponse(handlerResponse);

        /* asserts */
        assertThat(result).isNotNull();
        // "Carte" is mapped to "CARDS" via the localized-name fallback mapping
        assertThat(result.getPaymentMethodName()).isEqualTo("CARDS");
    }

    @Test
    void shouldHandleMaintenanceStatusByDefaultingToDisabled() {
        // Handler returns MAINTENANCE which has no equivalent in v2
        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("test")
                .paymentMethodDescription("desc")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto.PaymentMethodStatusEnum.MAINTENANCE
                )
                .asset("asset")
                .bundles(List.of());

        /* test */
        CalculateFeeResponseDto result = mapper.fromHandlerFeeResponse(handlerResponse);

        /* asserts */
        assertThat(result).isNotNull();
        assertThat(result.getPaymentMethodStatus())
                .isEqualTo(it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentMethodStatusDto.DISABLED);
    }

    @Test
    void shouldHandleNullPaymentMethodStatus() {
        // Handler returns null status
        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("test")
                .paymentMethodDescription("desc")
                .paymentMethodStatus(null)
                .asset("asset")
                .bundles(List.of());

        /* test */
        CalculateFeeResponseDto result = mapper.fromHandlerFeeResponse(handlerResponse);

        /* asserts */
        assertThat(result).isNotNull();
        assertThat(result.getPaymentMethodStatus()).isNull();
    }

    @Test
    void shouldHandleNullBundlesInResponse() {
        // Handler returns null bundles
        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("test")
                .paymentMethodDescription("desc")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto.PaymentMethodStatusEnum.ENABLED
                )
                .asset("asset")
                .bundles(null);

        /* test */
        CalculateFeeResponseDto result = mapper.fromHandlerFeeResponse(handlerResponse);

        /* asserts */
        assertThat(result).isNotNull();
        assertThat(result.getBundles()).isEmpty();
    }

    @Test
    void shouldMapAfmPaymentMethodNameToNpgEnum() {
        // Handler returns AFM-style payment method names; the bundle paymentMethod
        // must be normalized to the payment type code expected downstream
        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("test")
                .paymentMethodDescription("desc")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto.PaymentMethodStatusEnum.ENABLED
                )
                .asset("asset")
                .bundles(
                        List.of(
                                new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.BundleDto()
                                        .idPsp("psp1")
                                        .paymentMethod("Carte")
                                        .taxPayerFee(100L),
                                new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.BundleDto()
                                        .idPsp("psp2")
                                        .paymentMethod("Bancomat Pay")
                                        .taxPayerFee(50L),
                                new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.BundleDto()
                                        .idPsp("psp3")
                                        .paymentMethod("PayPal")
                                        .taxPayerFee(75L),
                                new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.BundleDto()
                                        .idPsp("psp4")
                                        .paymentMethod("UNKNOWN_METHOD")
                                        .taxPayerFee(200L),
                                new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.BundleDto()
                                        .idPsp("psp5")
                                        .paymentMethod(null)
                                        .taxPayerFee(0L)
                        )
                );

        /* test */
        CalculateFeeResponseDto result = mapper.fromHandlerFeeResponse(handlerResponse);

        /* asserts: AFM name -> NPG service name -> payment type code */
        assertThat(result).isNotNull();
        assertThat(result.getBundles()).hasSize(5);
        assertThat(result.getBundles().get(0).getPaymentMethod()).isEqualTo("CP"); // Carte
        assertThat(result.getBundles().get(1).getPaymentMethod()).isEqualTo("BPAY"); // Bancomat Pay
        assertThat(result.getBundles().get(2).getPaymentMethod()).isEqualTo("PPAL"); // PayPal
        assertThat(result.getBundles().get(3).getPaymentMethod()).isEqualTo("UNKNOWN_METHOD"); // fallback
        assertThat(result.getBundles().get(4).getPaymentMethod()).isNull(); // null stays null
    }

    @Test
    void shouldMapPaymentTypeCodeToNpgServiceName() {
        assertThat(PaymentMethodsHandlerMapper.mapPaymentTypeCodeToNpgServiceName("CP")).isEqualTo("CARDS");
        assertThat(PaymentMethodsHandlerMapper.mapPaymentTypeCodeToNpgServiceName("BPAY"))
                .isEqualTo("BANCOMATPAY");
        assertThat(PaymentMethodsHandlerMapper.mapPaymentTypeCodeToNpgServiceName("MYBK")).isEqualTo("MYBANK");
        assertThat(PaymentMethodsHandlerMapper.mapPaymentTypeCodeToNpgServiceName("PPAL")).isEqualTo("PAYPAL");
        assertThat(PaymentMethodsHandlerMapper.mapPaymentTypeCodeToNpgServiceName("APPL"))
                .isEqualTo("APPLEPAY");
        assertThat(PaymentMethodsHandlerMapper.mapPaymentTypeCodeToNpgServiceName("SATY"))
                .isEqualTo("SATISPAY");
        assertThat(PaymentMethodsHandlerMapper.mapPaymentTypeCodeToNpgServiceName("GOOG"))
                .isEqualTo("GOOGLEPAY");
        assertThat(PaymentMethodsHandlerMapper.mapPaymentTypeCodeToNpgServiceName("UNKNOWN"))
                .isEqualTo("UNKNOWN");
        assertThat(PaymentMethodsHandlerMapper.mapPaymentTypeCodeToNpgServiceName(null)).isNull();
    }
}
