package it.pagopa.transactions.controllers;

import it.pagopa.generated.payment.requests.api.PaymentRequestsApi;
import it.pagopa.generated.payment.requests.model.PaymentRequestsGetResponseDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class PaymentRequestsController implements PaymentRequestsApi {
    @Override
    public Mono<ResponseEntity<PaymentRequestsGetResponseDto>> getPaymentRequestInfo(String rptId, ServerWebExchange exchange) {
        return null;
    }
}
