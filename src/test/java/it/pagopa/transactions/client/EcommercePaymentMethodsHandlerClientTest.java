package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeResponseDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentNoticeDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.TransferListItemDto;
import it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.*;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.PaymentMethodNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcommercePaymentMethodsHandlerClientTest {

    private final it.pagopa.generated.ecommerce.paymentmethodshandler.v1.api.PaymentMethodsHandlerApi ecommercePaymentMethodsHandlerWebClientV1 = Mockito
            .mock(it.pagopa.generated.ecommerce.paymentmethodshandler.v1.api.PaymentMethodsHandlerApi.class);

    private final EcommercePaymentMethodsHandlerClient ecommercePaymentMethodsHandlerClient = new EcommercePaymentMethodsHandlerClient(
            ecommercePaymentMethodsHandlerWebClientV1
    );

    @Test
    void shouldReturnPaymentMethod() {
        String testId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";
        PaymentMethodResponseDto testPaymentMethodResponseDto = new PaymentMethodResponseDto();
        testPaymentMethodResponseDto
                .description(Map.of("it", "itDesc", "en", "enDesc"))
                .feeRange(new FeeRangeDto().min(1L).max(100L))
                .addPaymentMethodTypesItem(PaymentMethodResponseDto.PaymentMethodTypesEnum.CONTO)
                .status(PaymentMethodResponseDto.StatusEnum.ENABLED)
                .id(testId)
                .name(Map.of("it", "itName", "en", "enName"));

        /**
         * preconditions
         */
        when(ecommercePaymentMethodsHandlerWebClientV1.getPaymentMethod(testId, clientId))
                .thenReturn(Mono.just(testPaymentMethodResponseDto));

        /**
         * test
         */
        PaymentMethodResponseDto paymentMethodResponseDto = ecommercePaymentMethodsHandlerClient
                .getPaymentMethod(testId, clientId)
                .block();

        /**
         * asserts
         */
        assertThat(testPaymentMethodResponseDto.getId()).isEqualTo(paymentMethodResponseDto.getId());
    }

    @Test
    void shouldThrowInvalidRequestExceptionOnGetPaymentMethodErroring() {
        String testId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";

        /**
         * preconditions
         */
        when(ecommercePaymentMethodsHandlerWebClientV1.getPaymentMethod(testId, clientId))
                .thenReturn(
                        Mono.error(
                                WebClientResponseException.create(
                                        500,
                                        "Internal Server Error",
                                        HttpHeaders.EMPTY,
                                        null,
                                        Charset.defaultCharset(),
                                        null
                                )
                        )
                );

        /**
         * test
         */
        StepVerifier.create(ecommercePaymentMethodsHandlerClient.getPaymentMethod(testId, clientId))
                .expectError(InvalidRequestException.class)
                .verify();
    }

    @Test
    void shouldThrowPaymentMethodNotFoundExceptionOnGetPaymentMethodWhenReturning404() {
        String testId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";

        /**
         * preconditions
         */
        when(ecommercePaymentMethodsHandlerWebClientV1.getPaymentMethod(testId, clientId))
                .thenReturn(
                        Mono.error(
                                WebClientResponseException.create(
                                        404,
                                        "Not Found",
                                        HttpHeaders.EMPTY,
                                        null,
                                        Charset.defaultCharset(),
                                        null
                                )
                        )
                );

        /**
         * test
         */
        StepVerifier.create(ecommercePaymentMethodsHandlerClient.getPaymentMethod(testId, clientId))
                .expectError(PaymentMethodNotFoundException.class)
                .verify();
    }

    @ParameterizedTest
    @EnumSource(Transaction.ClientId.class)
    void shouldRequestPaymentMethodUsingOnlyCheckoutOrIO(Transaction.ClientId clientId) {
        final var paymentMethodId = UUID.randomUUID().toString();
        final var testPaymentMethodResponseDto = new PaymentMethodResponseDto()
                .description(Map.of("it", "itDesc", "en", "enDesc"))
                .feeRange(new FeeRangeDto().min(1L).max(100L))
                .addPaymentMethodTypesItem(PaymentMethodResponseDto.PaymentMethodTypesEnum.CONTO)
                .status(PaymentMethodResponseDto.StatusEnum.ENABLED)
                .id(paymentMethodId)
                .name(Map.of("it", "itName", "en", "enName"));

        when(ecommercePaymentMethodsHandlerWebClientV1.getPaymentMethod(any(), any()))
                .thenReturn(Mono.just(testPaymentMethodResponseDto));

        assertThat(ecommercePaymentMethodsHandlerClient.getPaymentMethod(paymentMethodId, clientId.name()).block())
                .satisfies(it -> assertThat(it.getId()).isEqualTo(paymentMethodId));

        final var clientIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(ecommercePaymentMethodsHandlerWebClientV1)
                .getPaymentMethod(eq(paymentMethodId), clientIdCaptor.capture());

        switch (clientId) {
            case CHECKOUT -> assertEquals("CHECKOUT", clientIdCaptor.getValue());
            case IO -> assertEquals("IO", clientIdCaptor.getValue());
            case CHECKOUT_CART, WISP_REDIRECT -> assertEquals("CHECKOUT_CART", clientIdCaptor.getValue());
        }
    }

    @Test
    void shouldReturnCalculateFeeResponse() {
        String paymentMethodId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";
        String language = "IT";
        Integer maxOccurrences = 5;

        CalculateFeeRequestDto feeRequest = new CalculateFeeRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeDto()
                                .paymentAmount(1000L)
                                .primaryCreditorInstitution("77777777777")
                                .addTransferListItem(
                                        new TransferListItemDto()
                                                .creditorInstitution("77777777777")
                                                .digitalStamp(false)
                                                .transferCategory("PO")
                                )
                )
                .bin("123456")
                .touchpoint("CHECKOUT")
                .idPspList(List.of("pspId"))
                .isAllCCP(false);

        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("VISA")
                .paymentMethodDescription("Carte di credito")
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

        /* preconditions */
        when(
                ecommercePaymentMethodsHandlerWebClientV1.calculateFees(
                        eq(paymentMethodId),
                        eq(clientId),
                        eq(language),
                        any(it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeRequestDto.class),
                        eq(maxOccurrences)
                )
        ).thenReturn(Mono.just(handlerResponse));

        /* test */
        CalculateFeeResponseDto result = ecommercePaymentMethodsHandlerClient
                .calculateFee(paymentMethodId, transactionId, feeRequest, maxOccurrences, clientId, language)
                .block();

        /* asserts */
        assertThat(result).isNotNull();
        assertThat(result.getPaymentMethodName()).isEqualTo("VISA");
        assertThat(result.getPaymentMethodDescription()).isEqualTo("Carte di credito");
        assertThat(result.getAsset()).isEqualTo("asset-url");
        assertThat(result.getBundles()).hasSize(1);
        assertThat(result.getBundles().get(0).getIdPsp()).isEqualTo("pspId");
        assertThat(result.getBundles().get(0).getTaxPayerFee()).isEqualTo(100L);
        assertThat(result.getBundles().get(0).getPspBusinessName()).isEqualTo("pspBusinessNameTest");
    }

    @Test
    void shouldThrowInvalidRequestExceptionOnCalculateFeeError() {
        String paymentMethodId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";
        String language = "IT";
        Integer maxOccurrences = 5;

        CalculateFeeRequestDto feeRequest = new CalculateFeeRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeDto()
                                .paymentAmount(1000L)
                                .primaryCreditorInstitution("77777777777")
                )
                .touchpoint("CHECKOUT")
                .isAllCCP(false);

        /* preconditions */
        when(
                ecommercePaymentMethodsHandlerWebClientV1.calculateFees(
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                )
        ).thenReturn(
                Mono.error(
                        WebClientResponseException.create(
                                500,
                                "Internal Server Error",
                                HttpHeaders.EMPTY,
                                null,
                                Charset.defaultCharset(),
                                null
                        )
                )
        );

        /* test */
        StepVerifier.create(
                ecommercePaymentMethodsHandlerClient.calculateFee(
                        paymentMethodId,
                        transactionId,
                        feeRequest,
                        maxOccurrences,
                        clientId,
                        language
                )
        )
                .expectError(InvalidRequestException.class)
                .verify();
    }

    @ParameterizedTest
    @EnumSource(Transaction.ClientId.class)
    void shouldMapClientIdCorrectlyOnCalculateFee(Transaction.ClientId clientId) {
        String paymentMethodId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String language = "EN";
        Integer maxOccurrences = 10;

        CalculateFeeRequestDto feeRequest = new CalculateFeeRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeDto()
                                .paymentAmount(2000L)
                                .primaryCreditorInstitution("77777777777")
                )
                .touchpoint("CHECKOUT")
                .isAllCCP(false);

        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("test")
                .paymentMethodDescription("desc")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto.PaymentMethodStatusEnum.ENABLED
                )
                .asset("asset")
                .bundles(List.of());

        when(ecommercePaymentMethodsHandlerWebClientV1.calculateFees(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(handlerResponse));

        /* test */
        ecommercePaymentMethodsHandlerClient
                .calculateFee(paymentMethodId, transactionId, feeRequest, maxOccurrences, clientId.name(), language)
                .block();

        /* asserts */
        final var clientIdCaptor = ArgumentCaptor.forClass(String.class);
        final var languageCaptor = ArgumentCaptor.forClass(String.class);
        verify(ecommercePaymentMethodsHandlerWebClientV1)
                .calculateFees(
                        eq(paymentMethodId),
                        clientIdCaptor.capture(),
                        languageCaptor.capture(),
                        any(),
                        eq(maxOccurrences)
                );

        assertEquals(language, languageCaptor.getValue());

        switch (clientId) {
            case CHECKOUT -> assertEquals("CHECKOUT", clientIdCaptor.getValue());
            case IO -> assertEquals("IO", clientIdCaptor.getValue());
            case CHECKOUT_CART, WISP_REDIRECT -> assertEquals("CHECKOUT_CART", clientIdCaptor.getValue());
        }
    }

    @Test
    void shouldDefaultToITWhenLanguageIsNull() {
        String paymentMethodId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";
        Integer maxOccurrences = 5;

        CalculateFeeRequestDto feeRequest = new CalculateFeeRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeDto()
                                .paymentAmount(1000L)
                                .primaryCreditorInstitution("77777777777")
                )
                .touchpoint("CHECKOUT")
                .isAllCCP(false);

        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("test")
                .paymentMethodDescription("desc")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto.PaymentMethodStatusEnum.ENABLED
                )
                .asset("asset")
                .bundles(List.of());

        when(ecommercePaymentMethodsHandlerWebClientV1.calculateFees(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(handlerResponse));

        /* test - pass null language */
        ecommercePaymentMethodsHandlerClient
                .calculateFee(paymentMethodId, transactionId, feeRequest, maxOccurrences, clientId, null)
                .block();

        /* asserts */
        final var languageCaptor = ArgumentCaptor.forClass(String.class);
        verify(ecommercePaymentMethodsHandlerWebClientV1)
                .calculateFees(any(), any(), languageCaptor.capture(), any(), any());

        assertEquals("IT", languageCaptor.getValue());
    }

    @Test
    void shouldMapPaymentNoticesCorrectly() {
        String paymentMethodId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";
        String language = "IT";
        Integer maxOccurrences = 5;

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

        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("test")
                .paymentMethodDescription("desc")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto.PaymentMethodStatusEnum.ENABLED
                )
                .asset("asset")
                .bundles(List.of());

        final var requestCaptor = ArgumentCaptor.forClass(
                it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeRequestDto.class
        );

        when(ecommercePaymentMethodsHandlerWebClientV1.calculateFees(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(handlerResponse));

        /* test */
        ecommercePaymentMethodsHandlerClient
                .calculateFee(paymentMethodId, transactionId, feeRequest, maxOccurrences, clientId, language)
                .block();

        /* asserts */
        verify(ecommercePaymentMethodsHandlerWebClientV1)
                .calculateFees(any(), any(), any(), requestCaptor.capture(), any());

        var capturedRequest = requestCaptor.getValue();
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
    void shouldHandleMaintenanceStatusByDefaultingToDisabled() {
        String paymentMethodId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";
        String language = "IT";
        Integer maxOccurrences = 5;

        CalculateFeeRequestDto feeRequest = new CalculateFeeRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeDto()
                                .paymentAmount(1000L)
                                .primaryCreditorInstitution("77777777777")
                                .addTransferListItem(
                                        new TransferListItemDto()
                                                .creditorInstitution("77777777777")
                                                .digitalStamp(false)
                                                .transferCategory("PO")
                                )
                )
                .touchpoint("CHECKOUT")
                .isAllCCP(false);

        // Handler returns MAINTENANCE which has no equivalent in v2
        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("test")
                .paymentMethodDescription("desc")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto.PaymentMethodStatusEnum.MAINTENANCE
                )
                .asset("asset")
                .bundles(List.of());

        when(ecommercePaymentMethodsHandlerWebClientV1.calculateFees(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(handlerResponse));

        /* test */
        CalculateFeeResponseDto result = ecommercePaymentMethodsHandlerClient
                .calculateFee(paymentMethodId, transactionId, feeRequest, maxOccurrences, clientId, language)
                .block();

        /* asserts */
        assertThat(result).isNotNull();
        assertThat(result.getPaymentMethodStatus())
                .isEqualTo(it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentMethodStatusDto.DISABLED);
    }

    @Test
    void shouldHandleNullPaymentMethodStatus() {
        String paymentMethodId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";
        String language = "IT";
        Integer maxOccurrences = 5;

        CalculateFeeRequestDto feeRequest = new CalculateFeeRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeDto()
                                .paymentAmount(1000L)
                                .primaryCreditorInstitution("77777777777")
                                .addTransferListItem(
                                        new TransferListItemDto()
                                                .creditorInstitution("77777777777")
                                                .digitalStamp(false)
                                                .transferCategory("PO")
                                )
                )
                .touchpoint("CHECKOUT")
                .isAllCCP(false);

        // Handler returns null status
        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("test")
                .paymentMethodDescription("desc")
                .paymentMethodStatus(null)
                .asset("asset")
                .bundles(List.of());

        when(ecommercePaymentMethodsHandlerWebClientV1.calculateFees(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(handlerResponse));

        /* test */
        CalculateFeeResponseDto result = ecommercePaymentMethodsHandlerClient
                .calculateFee(paymentMethodId, transactionId, feeRequest, maxOccurrences, clientId, language)
                .block();

        /* asserts */
        assertThat(result).isNotNull();
        assertThat(result.getPaymentMethodStatus()).isNull();
    }

    @Test
    void shouldHandleNullBundlesInResponse() {
        String paymentMethodId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";
        String language = "IT";
        Integer maxOccurrences = 5;

        CalculateFeeRequestDto feeRequest = new CalculateFeeRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeDto()
                                .paymentAmount(1000L)
                                .primaryCreditorInstitution("77777777777")
                                .addTransferListItem(
                                        new TransferListItemDto()
                                                .creditorInstitution("77777777777")
                                                .digitalStamp(false)
                                                .transferCategory("PO")
                                )
                )
                .touchpoint("CHECKOUT")
                .isAllCCP(false);

        // Handler returns null bundles
        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto handlerResponse = new it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto()
                .paymentMethodName("test")
                .paymentMethodDescription("desc")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeResponseDto.PaymentMethodStatusEnum.ENABLED
                )
                .asset("asset")
                .bundles(null);

        when(ecommercePaymentMethodsHandlerWebClientV1.calculateFees(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(handlerResponse));

        /* test */
        CalculateFeeResponseDto result = ecommercePaymentMethodsHandlerClient
                .calculateFee(paymentMethodId, transactionId, feeRequest, maxOccurrences, clientId, language)
                .block();

        /* asserts */
        assertThat(result).isNotNull();
        assertThat(result.getBundles()).isEmpty();
    }
}
