package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.*;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.BundleDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeResponseDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentNoticeDto;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.PaymentMethodNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcommercePaymentMethodsClientTest {

    private final it.pagopa.generated.ecommerce.paymentmethods.v1.api.PaymentMethodsApi ecommercePaymentMethodsWebClientV1 = Mockito
            .mock(it.pagopa.generated.ecommerce.paymentmethods.v1.api.PaymentMethodsApi.class);
    private final it.pagopa.generated.ecommerce.paymentmethods.v2.api.PaymentMethodsApi ecommercePaymentMethodsWebClientV2 = Mockito
            .mock(it.pagopa.generated.ecommerce.paymentmethods.v2.api.PaymentMethodsApi.class);

    private final EcommercePaymentMethodsClient ecommercePaymentMethodsClient = new EcommercePaymentMethodsClient(
            ecommercePaymentMethodsWebClientV1,
            ecommercePaymentMethodsWebClientV2
    );

    @Test
    void shouldReturnBundleList() {
        String paymentMethodId = UUID.randomUUID().toString();
        Integer TEST_MAX_OCCURRERNCES = 10;
        CalculateFeeRequestDto calculateFeeRequestDto = new CalculateFeeRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeDto()
                                .paymentAmount(10L)
                                .primaryCreditorInstitution("7777777777")
                )
                .bin("57497554")
                .touchpoint("CHECKOUT").idPspList(List.of("pspId"));

        CalculateFeeResponseDto bundleOptionDto = new CalculateFeeResponseDto().belowThreshold(true).bundles(
                List.of(
                        new BundleDto().abi("abiTest")
                                .bundleDescription("descriptionTest")
                                .bundleName("bundleNameTest")
                                .idBrokerPsp("idBrokerPspTest")
                                .idBundle("idBundleTest")
                                .idChannel("idChannelTest")
                                .idPsp("idPspTest")
                                .onUs(true)
                                .paymentMethod("idPaymentMethodTest")
                                .taxPayerFee(BigInteger.ZERO.longValue())
                                .touchpoint("CHECKOUT")
                )
        );

        /**
         * preconditions
         */
        when(
                ecommercePaymentMethodsWebClientV2
                        .calculateFees(
                                paymentMethodId,
                                TransactionTestUtils.TRANSACTION_ID,
                                calculateFeeRequestDto,
                                TEST_MAX_OCCURRERNCES
                        )
        )
                .thenReturn(Mono.just(bundleOptionDto));

        /**
         * test
         */
        CalculateFeeResponseDto calculateFeeResponseDto = ecommercePaymentMethodsClient
                .calculateFee(
                        paymentMethodId,
                        TransactionTestUtils.TRANSACTION_ID,
                        calculateFeeRequestDto,
                        TEST_MAX_OCCURRERNCES
                )
                .block();

        /**
         * asserts
         */
        assertThat(calculateFeeResponseDto).isEqualTo(bundleOptionDto);
    }

    @Test
    void shouldReturnPaymentMethod() {
        String TEST_ID = UUID.randomUUID().toString();
        String CLIENT_ID = "CHECKOUT";
        PaymentMethodResponseDto testPaymentMethodResponseDto = new PaymentMethodResponseDto();
        testPaymentMethodResponseDto
                .description("")
                .addRangesItem(new RangeDto().max(100L).min(0L))
                .paymentTypeCode("PO")
                .status(PaymentMethodStatusDto.ENABLED)
                .id(TEST_ID)
                .name("test");

        /**
         * preconditions
         */
        when(ecommercePaymentMethodsWebClientV1.getPaymentMethod(TEST_ID, CLIENT_ID))
                .thenReturn(Mono.just(testPaymentMethodResponseDto));

        /**
         * test
         */
        PaymentMethodResponseDto paymentMethodResponseDto = ecommercePaymentMethodsClient
                .getPaymentMethod(TEST_ID, CLIENT_ID)
                .block();

        /**
         * asserts
         */
        assertThat(testPaymentMethodResponseDto.getId()).isEqualTo(paymentMethodResponseDto.getId());
    }

    @Test
    void shouldReturnCardData() {
        String paymentMethodId = UUID.randomUUID().toString();
        String sessionId = "sessionId";

        SessionPaymentMethodResponseDto response = new SessionPaymentMethodResponseDto().bin("12345678")
                .sessionId(sessionId).brand("VISA").expiringDate("0226").lastFourDigits("1234");
        /**
         * preconditions
         */
        Mockito.when(
                ecommercePaymentMethodsWebClientV1
                        .getSessionPaymentMethod(
                                any(),
                                any()
                        )
        )
                .thenReturn(Mono.just(response));

        /**
         * test
         */
        StepVerifier
                .create(
                        ecommercePaymentMethodsClient
                                .retrieveCardData(paymentMethodId, sessionId)
                )
                .expectNext(response)
                .verifyComplete();

    }

    @Test
    void shouldReturnErrorFromRetrieveCardData() {
        String paymentMethodId = UUID.randomUUID().toString();
        String sessionId = "sessionId";

        SessionPaymentMethodResponseDto response = new SessionPaymentMethodResponseDto().bin("12345678")
                .sessionId(sessionId).brand("VISA").expiringDate("0226").lastFourDigits("1234");
        /**
         * preconditions
         */
        Mockito.when(
                ecommercePaymentMethodsWebClientV1
                        .getSessionPaymentMethod(
                                any(),
                                any()
                        )
        )
                .thenReturn(
                        Mono.error(
                                new WebClientResponseException(
                                        HttpStatus.NOT_FOUND.value(),
                                        "SessionId not found",
                                        null,
                                        null,
                                        null
                                )
                        )
                );

        /**
         * test
         */
        StepVerifier.create(ecommercePaymentMethodsClient.retrieveCardData(paymentMethodId, sessionId))
                .expectErrorMatches(
                        e -> e instanceof InvalidRequestException invalidRequestException
                                && invalidRequestException.getMessage()
                                        .equals("Error while invoke method retrieve card data")
                )
                .verify();
    }

    @Test
    void shouldReturnSuccessfulMonoWhenSuccessfullyUpdatingSession() {
        String paymentMethodId = UUID.randomUUID().toString();
        String sessionId = "sessionId";
        String transactionId = TransactionTestUtils.TRANSACTION_ID;

        /* preconditions */
        Mockito.when(
                ecommercePaymentMethodsWebClientV1.updateSession(
                        paymentMethodId,
                        sessionId,
                        new PatchSessionRequestDto().transactionId(transactionId)
                )
        )
                .thenReturn(Mono.empty());

        /* test */
        StepVerifier.create(ecommercePaymentMethodsClient.updateSession(paymentMethodId, sessionId, transactionId))
                .verifyComplete();
    }

    @Test
    void shouldReturnInvalidRequestExceptionOnClientExceptionWhenUpdatingSession() {
        String paymentMethodId = UUID.randomUUID().toString();
        String sessionId = "sessionId";
        String transactionId = TransactionTestUtils.TRANSACTION_ID;

        /* preconditions */
        Mockito.when(
                ecommercePaymentMethodsWebClientV1.updateSession(
                        paymentMethodId,
                        sessionId,
                        new PatchSessionRequestDto().transactionId(transactionId)
                )
        )
                .thenReturn(Mono.error(new WebClientResponseException(500, "Internal Server Error", null, null, null)));

        /* test */
        StepVerifier.create(ecommercePaymentMethodsClient.updateSession(paymentMethodId, sessionId, transactionId))
                .expectError(InvalidRequestException.class)
                .verify();
    }

    @Test
    void shouldThrowInvalidRequestExceptionOnGetPaymentMethodErroring() {
        String TEST_ID = UUID.randomUUID().toString();
        String CLIENT_ID = "CHECKOUT";

        /**
         * preconditions
         */
        when(ecommercePaymentMethodsWebClientV1.getPaymentMethod(TEST_ID, CLIENT_ID))
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
        StepVerifier.create(ecommercePaymentMethodsClient.getPaymentMethod(TEST_ID, CLIENT_ID))
                .expectError(InvalidRequestException.class)
                .verify();
    }

    @Test
    void shouldThrowPaymentMethodNotFoundExceptionOnGetPaymentMethodWhenReturning404() {
        String TEST_ID = UUID.randomUUID().toString();
        String CLIENT_ID = "CHECKOUT";

        /**
         * preconditions
         */
        when(ecommercePaymentMethodsWebClientV1.getPaymentMethod(TEST_ID, CLIENT_ID))
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
        StepVerifier.create(ecommercePaymentMethodsClient.getPaymentMethod(TEST_ID, CLIENT_ID))
                .expectError(PaymentMethodNotFoundException.class)
                .verify();
    }

    @ParameterizedTest
    @EnumSource(Transaction.ClientId.class)
    void shouldRequestPaymentMethodUsingOnlyCheckoutOrIO(Transaction.ClientId clientId) {
        final var paymentMethodId = UUID.randomUUID().toString();
        final var testPaymentMethodResponseDto = new PaymentMethodResponseDto()
                .description("")
                .addRangesItem(new RangeDto().max(100L).min(0L))
                .paymentTypeCode("PO")
                .status(PaymentMethodStatusDto.ENABLED)
                .id(paymentMethodId)
                .name("test");

        when(ecommercePaymentMethodsWebClientV1.getPaymentMethod(any(), any()))
                .thenReturn(Mono.just(testPaymentMethodResponseDto));

        assertThat(ecommercePaymentMethodsClient.getPaymentMethod(paymentMethodId, clientId.name()).block())
                .satisfies((it) -> assertThat(it.getId()).isEqualTo(paymentMethodId));

        final var clientIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(ecommercePaymentMethodsWebClientV1).getPaymentMethod(eq(paymentMethodId), clientIdCaptor.capture());
        assertThat(clientIdCaptor.getValue().equals("IO") || clientIdCaptor.getValue().equals("CHECKOUT")).isTrue();
    }
}
