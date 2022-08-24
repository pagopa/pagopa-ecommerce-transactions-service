package it.pagopa.transactions.controllers;

import io.lettuce.core.RedisConnectionException;
import it.pagopa.generated.payment.requests.api.PaymentRequestsApi;
import it.pagopa.generated.payment.requests.model.PaymentRequestsGetResponseDto;
import it.pagopa.generated.transactions.server.model.ProblemJsonDto;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.UnsatisfiablePspRequestException;
import it.pagopa.transactions.services.PaymentRequestsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class PaymentRequestsController implements PaymentRequestsApi {

  @Autowired private PaymentRequestsService paymentRequestsService;

  @Override
  public Mono<ResponseEntity<PaymentRequestsGetResponseDto>> getPaymentRequestInfo(
      String rptId, ServerWebExchange exchange) {
    return Mono.just(rptId)
        .flatMap(paymentRequestsService::getPaymentRequestInfo)
        .map(ResponseEntity::ok);
  }

  @ExceptionHandler({
    RedisSystemException.class,
    RedisConnectionException.class,
    WebClientRequestException.class
  })
  private ResponseEntity<ProblemJsonDto> genericBadGatewayHandler(RuntimeException exception) {
    return new ResponseEntity<>(
        new ProblemJsonDto().status(502).title("Bad gateway"), HttpStatus.BAD_GATEWAY);
  }
}
