package it.pagopa.transactions.controllers;

import it.pagopa.generated.payment.requests.model.PaymentRequestsGetResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.services.PaymentRequestsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRequestsControllerTest {

  @InjectMocks private PaymentRequestsController paymentRequestsController;

  @Mock private PaymentRequestsService paymentRequestsService;

  @Test
  void shouldGetPaymentInfoGivenValidRptid() {
    String RPTID = "77777777777302016723749670035";

    PaymentRequestsGetResponseDto response = new PaymentRequestsGetResponseDto();
    response.setRptId(RPTID);
    response.amount(1000);
    response.setDescription("Payment test");
    when(paymentRequestsService.getPaymentRequestInfo(RPTID)).thenReturn(Mono.just(response));

    ResponseEntity<PaymentRequestsGetResponseDto> responseEntity =
        paymentRequestsController.getPaymentRequestInfo(RPTID, null).block();

    assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
  }
}
