package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeResponseDto;
import it.pagopa.generated.ecommerce.paymentmethodshandler.v1.api.PaymentMethodsHandlerApi;
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

import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class EcommercePaymentMethodsHandlerClient {

    private final PaymentMethodsHandlerApi ecommercePaymentMethodsHandlerWebClientV1;

    @Autowired
    public EcommercePaymentMethodsHandlerClient(
            @Qualifier(
                "ecommercePaymentMethodHandlerWebClientV1"
            ) PaymentMethodsHandlerApi ecommercePaymentMethodsHandlerWebClientV1
    ) {
        this.ecommercePaymentMethodsHandlerWebClientV1 = ecommercePaymentMethodsHandlerWebClientV1;
    }

    public Mono<PaymentMethodResponseDto> getPaymentMethod(
                                                           String paymentMethodId,
                                                           String xClientId
    ) {
        String clientId = resolveSupportedClientId(xClientId);

        return ecommercePaymentMethodsHandlerWebClientV1.getPaymentMethod(paymentMethodId, clientId)
                .doOnNext(v -> logSuccess("Retrieved payment method"))
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsHandlerClient::logWebClientException
                )
                .onErrorMap(
                        err -> err instanceof WebClientResponseException.NotFound
                                ? new PaymentMethodNotFoundException(paymentMethodId, xClientId)
                                : new InvalidRequestException("Error while invoking getPaymentMethod")
                );
    }

    public Mono<SessionPaymentMethodResponseDto> retrieveCardData(
                                                                  String paymentMethodId,
                                                                  String orderId,
                                                                  String xClientId
    ) {
        String clientId = resolveSupportedClientId(xClientId);

        return ecommercePaymentMethodsHandlerWebClientV1.getSessionPaymentMethod(paymentMethodId, orderId, clientId)
                .doOnNext(v -> logSuccess("Retrieved session payment method"))
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsHandlerClient::logWebClientException
                )
                .onErrorMap(err -> new InvalidRequestException("Error while invoke method retrieve card data"));
    }

    /**
     * The payment-methods-handler only supports CHECKOUT_CART, CHECKOUT, and IO.
     * Maps WISP_REDIRECT to CHECKOUT_CART.
     */
    private String resolveSupportedClientId(String xClientId) {
        Transaction.ClientId parsedClient = Transaction.ClientId.fromString(xClientId);
        return (parsedClient == Transaction.ClientId.WISP_REDIRECT
                ? Transaction.ClientId.CHECKOUT_CART
                : parsedClient).name();
    }

    private static void logSuccess(String message) {
        LogTracingUtils.loggerTracingUtils()
                .dependency(LogTracingUtils.PAYMENT_METHODS_HANDLER_DEPENDENCY)
                .success()
                .logInfo(log, message);
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
