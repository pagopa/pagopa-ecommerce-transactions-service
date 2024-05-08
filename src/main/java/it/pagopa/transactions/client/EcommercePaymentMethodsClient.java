package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.PatchSessionRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.PaymentMethodResponseDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.SessionPaymentMethodResponseDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeResponseDto;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.PaymentMethodNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class EcommercePaymentMethodsClient {

    private final it.pagopa.generated.ecommerce.paymentmethods.v1.api.PaymentMethodsApi ecommercePaymentMethodsWebClientV1;

    private final it.pagopa.generated.ecommerce.paymentmethods.v2.api.PaymentMethodsApi ecommercePaymentMethodsWebClientV2;

    @Autowired
    public EcommercePaymentMethodsClient(
            @Qualifier(
                "ecommercePaymentMethodWebClientV1"
            ) it.pagopa.generated.ecommerce.paymentmethods.v1.api.PaymentMethodsApi ecommercePaymentMethodsWebClientV1,
            @Qualifier(
                "ecommercePaymentMethodWebClientV2"
            ) it.pagopa.generated.ecommerce.paymentmethods.v2.api.PaymentMethodsApi ecommercePaymentMethodsWebClientV2
    ) {
        this.ecommercePaymentMethodsWebClientV1 = ecommercePaymentMethodsWebClientV1;
        this.ecommercePaymentMethodsWebClientV2 = ecommercePaymentMethodsWebClientV2;
    }

    public Mono<CalculateFeeResponseDto> calculateFee(
                                                      String paymentMethodId,
                                                      String transactionId,
                                                      CalculateFeeRequestDto calculateFeeRequestDto,
                                                      Integer maxOccurrences

    ) {
        return ecommercePaymentMethodsWebClientV2
                .calculateFees(paymentMethodId, transactionId, calculateFeeRequestDto, maxOccurrences)
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsClient::logWebClientException
                )
                .onErrorMap(
                        err -> new InvalidRequestException("Error while invoke method for read psp list")
                );
    }

    public Mono<PaymentMethodResponseDto> getPaymentMethod(
                                                           String paymentMethodId,
                                                           String xClientId
    ) {
        return ecommercePaymentMethodsWebClientV1.getPaymentMethod(paymentMethodId, xClientId)
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsClient::logWebClientException
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

    public Mono<SessionPaymentMethodResponseDto> retrieveCardData(
                                                                  String paymentMethodId,
                                                                  String orderId

    ) {
        return ecommercePaymentMethodsWebClientV1
                .getSessionPaymentMethod(paymentMethodId, orderId)
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsClient::logWebClientException
                )
                .onErrorMap(
                        err -> new InvalidRequestException("Error while invoke method retrieve card data")
                );
    }

    public Mono<Void> updateSession(
                                    String paymentMethodId,
                                    String orderId,
                                    String transactionId
    ) {
        return ecommercePaymentMethodsWebClientV1
                .updateSession(paymentMethodId, orderId, new PatchSessionRequestDto().transactionId(transactionId))
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsClient::logWebClientException
                )
                .onErrorMap(
                        err -> new InvalidRequestException("Error while invoke method update session")
                );
    }

    private static void logWebClientException(WebClientResponseException e) {
        log.info(
                "Got bad response from payment-methods-service [HTTP {}]: {}",
                e.getStatusCode(),
                e.getResponseBodyAsString()
        );
    }
}
