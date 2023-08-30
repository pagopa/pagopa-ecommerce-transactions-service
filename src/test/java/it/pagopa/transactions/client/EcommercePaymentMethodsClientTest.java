package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.paymentmethods.v1.api.PaymentMethodsApi;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.*;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EcommercePaymentMethodsClientTest {

    @InjectMocks
    private EcommercePaymentMethodsClient ecommercePaymentMethodsClient;

    @Mock
    private PaymentMethodsApi ecommercePaymentInstrumentsWebClient;

    @Test
    void shouldReturnBundleList() {
        String paymentMethodId = UUID.randomUUID().toString();
        Integer TEST_MAX_OCCURRERNCES = 10;
        CalculateFeeRequestDto calculateFeeRequestDto = new CalculateFeeRequestDto()
                .paymentAmount(BigInteger.TEN.longValue()).bin("57497554")
                .touchpoint("CHECKOUT").primaryCreditorInstitution("7777777777").idPspList(List.of("pspId"));

        CalculateFeeResponseDto bundleOptionDto = new CalculateFeeResponseDto().belowThreshold(true).bundles(
                List.of(
                        new BundleDto().abi("abiTest")
                                .bundleDescription("descriptionTest")
                                .bundleName("bundleNameTest")
                                .idBrokerPsp("idBrokerPspTest")
                                .idBundle("idBundleTest")
                                .idChannel("idChannelTest")
                                .idCiBundle("idCiBundleTest")
                                .idPsp("idPspTest")
                                .onUs(true)
                                .paymentMethod("idPaymentMethodTest")
                                .primaryCiIncurredFee(BigInteger.ZERO.longValue())
                                .taxPayerFee(BigInteger.ZERO.longValue())
                                .touchpoint("CHECKOUT")
                )
        );

        /**
         * preconditions
         */
        when(
                ecommercePaymentInstrumentsWebClient
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
        when(ecommercePaymentInstrumentsWebClient.getPaymentMethod(TEST_ID))
                .thenReturn(Mono.just(testPaymentMethodResponseDto));

        /**
         * test
         */
        PaymentMethodResponseDto paymentMethodResponseDto = ecommercePaymentMethodsClient.getPaymentMethod(TEST_ID)
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
                ecommercePaymentInstrumentsWebClient
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
                ecommercePaymentInstrumentsWebClient
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

}
