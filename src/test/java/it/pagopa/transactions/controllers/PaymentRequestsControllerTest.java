package it.pagopa.transactions.controllers;

import it.pagopa.generated.payment.requests.model.PaymentRequestsGetResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class PaymentRequestsControllerTest {

    @InjectMocks
    private PaymentRequestsController paymentRequestsController;

    @Test
    void shouldGetOk() {
        String RPTID = "77777777777302016723749670035";

        ResponseEntity<PaymentRequestsGetResponseDto> responseEntity = paymentRequestsController
                .getPaymentRequestInfo(RPTID, null).block();

        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(responseEntity.getBody(), null);
    }
}
