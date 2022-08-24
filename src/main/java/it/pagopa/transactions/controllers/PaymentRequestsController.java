package it.pagopa.transactions.controllers;

import io.lettuce.core.RedisConnectionException;
import it.pagopa.generated.payment.requests.api.PaymentRequestsApi;
import it.pagopa.generated.payment.requests.model.*;
import it.pagopa.generated.transactions.server.model.ProblemJsonDto;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.NodoErrorException;
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

import java.util.Arrays;
import java.util.Random;
import java.util.random.RandomGenerator;

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

  @ExceptionHandler({
    NodoErrorException.class,
  })
  private ResponseEntity<?> nodoErrorHandler(NodoErrorException exception) {

    return switch (exception.getFaultCode()) {
       case String s && Arrays.stream(PartyConfigurationFaultDto.values()).anyMatch( z -> z.getValue().equals(s)) -> new ResponseEntity<>(
               new PartyConfigurationFaultPaymentProblemJsonDto()
                       .title("EC error")
                       .faultCodeCategory(FaultCategoryDto.PAYMENT_UNAVAILABLE)
                       .faultCodeDetail(PartyConfigurationFaultDto.fromValue(s)), HttpStatus.BAD_GATEWAY);
      case String s && Arrays.stream(ValidationFaultDto.values()).anyMatch( z -> z.getValue().equals(s)) -> new ResponseEntity<>(
              new ValidationFaultPaymentProblemJsonDto()
                      .title("Validation Fault")
                      .faultCodeCategory(FaultCategoryDto.PAYMENT_UNKNOWN)
                      .faultCodeDetail(ValidationFaultDto.fromValue(s)), HttpStatus.NOT_FOUND);
      case String s && Arrays.stream(GatewayFaultDto.values()).anyMatch( z -> z.getValue().equals(s)) -> new ResponseEntity<>(
              new GatewayFaultPaymentProblemJsonDto()
                      .title("Payment unavailable")
                      .faultCodeCategory(FaultCategoryDto.GENERIC_ERROR)
                      .faultCodeDetail(GatewayFaultDto.fromValue(s)), HttpStatus.BAD_GATEWAY);
      case String s && Arrays.stream(GatewayFaultDto.values()).anyMatch( z -> z.getValue().equals(s)) -> new ResponseEntity<>(
              new PartyTimeoutFaultPaymentProblemJsonDto()
                      .title("Gateway Timeout")
                      .faultCodeCategory(FaultCategoryDto.GENERIC_ERROR)
                      .faultCodeDetail(PartyTimeoutFaultDto.fromValue(s)), HttpStatus.GATEWAY_TIMEOUT);
      case String s && Arrays.stream(PaymentStatusFaultDto.values()).anyMatch( z -> z.getValue().equals(s)) -> new ResponseEntity<>(
              new PaymentStatusFaultPaymentProblemJsonDto()
                      .title("Payment Status Fault")
                      .faultCodeCategory(FaultCategoryDto.GENERIC_ERROR)
                      .faultCodeDetail(PaymentStatusFaultDto.fromValue(s)), HttpStatus.GATEWAY_TIMEOUT);
       default -> new ResponseEntity<>(
               new ProblemJsonDto().title("Bad gateway"), HttpStatus.BAD_GATEWAY);
    };
  }
}
