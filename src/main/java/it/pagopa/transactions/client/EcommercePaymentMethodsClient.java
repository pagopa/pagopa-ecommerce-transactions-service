package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.paymentmethods.v1.api.PaymentMethodsApi;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.*;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class EcommercePaymentMethodsClient {

    @Autowired
    @Qualifier("ecommercePaymentInstrumentsWebClient")
    private PaymentMethodsApi ecommercePaymentInstrumentsWebClient;

    public Mono<CalculateFeeResponseDto> calculateFee(
                                                      String paymentMethodId,
                                                      String transactionId,
                                                      CalculateFeeRequestDto calculateFeeRequestDto,
                                                      Integer maxOccurrences

    ) {
        return ecommercePaymentInstrumentsWebClient
                .calculateFees(paymentMethodId, transactionId, calculateFeeRequestDto, maxOccurrences)
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsClient::logWebClientException
                )
                .onErrorMap(
                        err -> new InvalidRequestException("Error while invoke method for read psp list")
                );
    }

    public Mono<PaymentMethodResponseDto> getPaymentMethod(String paymentMethodId) {
        return ecommercePaymentInstrumentsWebClient.getPaymentMethod(paymentMethodId);
    }

    public Mono<SessionPaymentMethodResponseDto> retrieveCardData(
                                                                  String paymentMethodId,
                                                                  String sessionId

    ) {
        return ecommercePaymentInstrumentsWebClient
                .getSessionPaymentMethod(paymentMethodId, sessionId)
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
                                    String sessionId,
                                    String transactionId
    ) {
        return ecommercePaymentInstrumentsWebClient
                .updateSession(paymentMethodId, sessionId, new PatchSessionRequestDto().transactionId(transactionId))
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
