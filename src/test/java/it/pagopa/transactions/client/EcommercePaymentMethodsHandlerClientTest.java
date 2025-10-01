package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
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
}
