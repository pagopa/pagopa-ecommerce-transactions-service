package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeResponseDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentNoticeDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.TransferListItemDto;
import it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.*;
import it.pagopa.transactions.client.mappers.PaymentMethodsHandlerMapper;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.PaymentMethodNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import it.pagopa.generated.ecommerce.paymentmethodshandler.v1.api.PaymentMethodsHandlerApi;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

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

    private final PaymentMethodsHandlerApi ecommercePaymentMethodsHandlerWebClientV1 = mock(
            PaymentMethodsHandlerApi.class
    );
    private final PaymentMethodsHandlerMapper mapper = spy(new PaymentMethodsHandlerMapper());
    private final EcommercePaymentMethodsHandlerClient ecommercePaymentMethodsHandlerClient = new EcommercePaymentMethodsHandlerClient(
            ecommercePaymentMethodsHandlerWebClientV1,
            mapper
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

        assertThat(
                ecommercePaymentMethodsHandlerClient.getPaymentMethod(paymentMethodId, clientId.name())
                        .block()
        )
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
                                                .creditorInstitution(
                                                        "77777777777"
                                                )
                                                .digitalStamp(false)
                                                .transferCategory(
                                                        "PO"
                                                )
                                )
                )
                .bin("123456")
                .touchpoint("CHECKOUT")
                .idPspList(List.of("pspId"))
                .isAllCCP(false);

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

        /* preconditions */
        when(
                ecommercePaymentMethodsHandlerWebClientV1.calculateFees(
                        eq(paymentMethodId),
                        eq(clientId),
                        any(it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.CalculateFeeRequestDto.class),
                        eq(maxOccurrences),
                        eq(language)
                )
        )
                .thenReturn(Mono.just(handlerResponse));

        /* test */
        CalculateFeeResponseDto result = ecommercePaymentMethodsHandlerClient
                .calculateFee(
                        paymentMethodId,
                        feeRequest,
                        maxOccurrences,
                        clientId,
                        language
                )
                .block();

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
        verify(mapper).toHandlerFeeRequest(feeRequest);
        verify(mapper).fromHandlerFeeResponse(handlerResponse);
    }

    @Test
    void shouldThrowInvalidRequestExceptionOnCalculateFeeError() {
        String paymentMethodId = UUID.randomUUID().toString();
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
        )
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

        /* test */
        StepVerifier.create(
                ecommercePaymentMethodsHandlerClient.calculateFee(
                        paymentMethodId,
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
                .calculateFee(
                        paymentMethodId,
                        feeRequest,
                        maxOccurrences,
                        clientId.name(),
                        language
                )
                .block();

        /* asserts */
        final var clientIdCaptor = ArgumentCaptor.forClass(String.class);
        final var languageCaptor = ArgumentCaptor.forClass(String.class);
        verify(ecommercePaymentMethodsHandlerWebClientV1)
                .calculateFees(
                        eq(paymentMethodId),
                        clientIdCaptor.capture(),
                        any(),
                        eq(maxOccurrences),
                        languageCaptor.capture()
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
                .calculateFee(
                        paymentMethodId,
                        feeRequest,
                        maxOccurrences,
                        clientId,
                        null
                )
                .block();

        /* asserts */
        final var languageCaptor = ArgumentCaptor.forClass(String.class);
        verify(ecommercePaymentMethodsHandlerWebClientV1)
                .calculateFees(any(), any(), any(), any(), languageCaptor.capture());

        assertEquals("IT", languageCaptor.getValue());
    }

    @Test
    void shouldRetrieveCardData() {
        String paymentMethodId = UUID.randomUUID().toString();
        String orderId = "orderId";
        String clientId = "CHECKOUT";

        SessionPaymentMethodResponseDto response = new SessionPaymentMethodResponseDto()
                .bin("12345678")
                .sessionId("sessionId")
                .brand("VISA")
                .expiringDate("0226")
                .lastFourDigits("1234");

        /* preconditions */
        when(
                ecommercePaymentMethodsHandlerWebClientV1.getSessionPaymentMethod(
                        paymentMethodId,
                        orderId,
                        clientId
                )
        )
                .thenReturn(Mono.just(response));

        /* test */
        StepVerifier.create(
                ecommercePaymentMethodsHandlerClient.retrieveCardData(
                        paymentMethodId,
                        orderId,
                        clientId
                )
        )
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void shouldThrowInvalidRequestExceptionOnRetrieveCardDataError() {
        String paymentMethodId = UUID.randomUUID().toString();
        String orderId = "orderId";
        String clientId = "CHECKOUT";

        /* preconditions */
        when(
                ecommercePaymentMethodsHandlerWebClientV1.getSessionPaymentMethod(
                        paymentMethodId,
                        orderId,
                        clientId
                )
        )
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

        /* test */
        StepVerifier.create(
                ecommercePaymentMethodsHandlerClient.retrieveCardData(
                        paymentMethodId,
                        orderId,
                        clientId
                )
        )
                .expectError(InvalidRequestException.class)
                .verify();
    }

    @ParameterizedTest
    @EnumSource(Transaction.ClientId.class)
    void shouldMapClientIdCorrectlyOnRetrieveCardData(Transaction.ClientId clientId) {
        String paymentMethodId = UUID.randomUUID().toString();
        String orderId = "orderId";

        SessionPaymentMethodResponseDto response = new SessionPaymentMethodResponseDto()
                .bin("12345678")
                .sessionId("sessionId")
                .brand("VISA");

        when(ecommercePaymentMethodsHandlerWebClientV1.getSessionPaymentMethod(any(), any(), any()))
                .thenReturn(Mono.just(response));

        /* test */
        ecommercePaymentMethodsHandlerClient.retrieveCardData(paymentMethodId, orderId, clientId.name())
                .block();

        /* asserts */
        final var clientIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(ecommercePaymentMethodsHandlerWebClientV1)
                .getSessionPaymentMethod(eq(paymentMethodId), eq(orderId), clientIdCaptor.capture());

        switch (clientId) {
            case CHECKOUT -> assertEquals("CHECKOUT", clientIdCaptor.getValue());
            case IO -> assertEquals("IO", clientIdCaptor.getValue());
            case CHECKOUT_CART, WISP_REDIRECT -> assertEquals("CHECKOUT_CART", clientIdCaptor.getValue());
        }
    }

    @Test
    void shouldUpdateSession() {
        String paymentMethodId = UUID.randomUUID().toString();
        String orderId = "orderId";
        String transactionId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";

        /* preconditions */
        when(
                ecommercePaymentMethodsHandlerWebClientV1.updateSession(
                        paymentMethodId,
                        orderId,
                        clientId,
                        new PatchSessionRequestDto().transactionId(transactionId)
                )
        )
                .thenReturn(Mono.empty());

        /* test */
        StepVerifier
                .create(
                        ecommercePaymentMethodsHandlerClient
                                .updateSession(
                                        paymentMethodId,
                                        orderId,
                                        transactionId,
                                        clientId
                                )
                )
                .verifyComplete();
    }

    @Test
    void shouldThrowInvalidRequestExceptionOnUpdateSessionError() {
        String paymentMethodId = UUID.randomUUID().toString();
        String orderId = "orderId";
        String transactionId = UUID.randomUUID().toString();
        String clientId = "CHECKOUT";

        /* preconditions */
        when(
                ecommercePaymentMethodsHandlerWebClientV1.updateSession(
                        paymentMethodId,
                        orderId,
                        clientId,
                        new PatchSessionRequestDto().transactionId(transactionId)
                )
        )
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

        /* test */
        StepVerifier
                .create(
                        ecommercePaymentMethodsHandlerClient
                                .updateSession(
                                        paymentMethodId,
                                        orderId,
                                        transactionId,
                                        clientId
                                )
                )
                .expectError(InvalidRequestException.class)
                .verify();
    }

    @ParameterizedTest
    @EnumSource(Transaction.ClientId.class)
    void shouldMapClientIdCorrectlyOnUpdateSession(Transaction.ClientId clientId) {
        String paymentMethodId = UUID.randomUUID().toString();
        String orderId = "orderId";
        String transactionId = UUID.randomUUID().toString();

        when(ecommercePaymentMethodsHandlerWebClientV1.updateSession(any(), any(), any(), any()))
                .thenReturn(Mono.empty());

        /* test */
        ecommercePaymentMethodsHandlerClient
                .updateSession(paymentMethodId, orderId, transactionId, clientId.name())
                .block();

        /* asserts */
        final var clientIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(ecommercePaymentMethodsHandlerWebClientV1)
                .updateSession(eq(paymentMethodId), eq(orderId), clientIdCaptor.capture(), any());

        switch (clientId) {
            case CHECKOUT -> assertEquals("CHECKOUT", clientIdCaptor.getValue());
            case IO -> assertEquals("IO", clientIdCaptor.getValue());
            case CHECKOUT_CART, WISP_REDIRECT -> assertEquals("CHECKOUT_CART", clientIdCaptor.getValue());
        }
    }

    @Test
    void shouldMapPaymentTypeCodeToNpgServiceName() {
        assertThat(EcommercePaymentMethodsHandlerClient.mapPaymentTypeCodeToNpgServiceName("CP"))
                .isEqualTo("CARDS");
        assertThat(EcommercePaymentMethodsHandlerClient.mapPaymentTypeCodeToNpgServiceName("BPAY"))
                .isEqualTo("BANCOMATPAY");
        assertThat(EcommercePaymentMethodsHandlerClient.mapPaymentTypeCodeToNpgServiceName("MYBK"))
                .isEqualTo("MYBANK");
        assertThat(EcommercePaymentMethodsHandlerClient.mapPaymentTypeCodeToNpgServiceName("PPAL"))
                .isEqualTo("PAYPAL");
        assertThat(EcommercePaymentMethodsHandlerClient.mapPaymentTypeCodeToNpgServiceName("APPL"))
                .isEqualTo("APPLEPAY");
        assertThat(EcommercePaymentMethodsHandlerClient.mapPaymentTypeCodeToNpgServiceName("SATY"))
                .isEqualTo("SATISPAY");
        assertThat(EcommercePaymentMethodsHandlerClient.mapPaymentTypeCodeToNpgServiceName("GOOG"))
                .isEqualTo("GOOGLEPAY");
        assertThat(EcommercePaymentMethodsHandlerClient.mapPaymentTypeCodeToNpgServiceName("UNKNOWN"))
                .isEqualTo("UNKNOWN");
        assertThat(EcommercePaymentMethodsHandlerClient.mapPaymentTypeCodeToNpgServiceName(null)).isNull();
    }
}
