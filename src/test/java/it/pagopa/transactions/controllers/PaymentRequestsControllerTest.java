package it.pagopa.transactions.controllers;

import it.pagopa.generated.payment.requests.model.*;
import it.pagopa.generated.transactions.server.model.ProblemJsonDto;
import it.pagopa.transactions.exceptions.NodoErrorException;
import it.pagopa.transactions.services.PaymentRequestsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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

  @Test
  void shouldGenericBadGatewayResponse()
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    Method method =
        PaymentRequestsController.class.getDeclaredMethod(
            "genericBadGatewayHandler", RuntimeException.class);
    method.setAccessible(true);

    ResponseEntity<ProblemJsonDto> responseEntity =
        (ResponseEntity<ProblemJsonDto>)
            method.invoke(paymentRequestsController, new RuntimeException());

    assertEquals(responseEntity != null, Boolean.TRUE);
    assertEquals(responseEntity.getStatusCode(), HttpStatus.BAD_GATEWAY);
  }

  @Test
  void shouldReturnResponseEntityWithPartyConfigurationFault()
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

    Method method =
        PaymentRequestsController.class.getDeclaredMethod(
            "nodoErrorHandler", NodoErrorException.class);
    method.setAccessible(true);

    ResponseEntity<PartyConfigurationFaultPaymentProblemJsonDto> responseEntity =
        (ResponseEntity<PartyConfigurationFaultPaymentProblemJsonDto>)
            method.invoke(
                paymentRequestsController,
                new NodoErrorException(
                    PartyConfigurationFaultDto.PPT_DOMINIO_DISABILITATO.getValue()));

    assertEquals(responseEntity != null, Boolean.TRUE);
    assertEquals(responseEntity.getStatusCode(), HttpStatus.BAD_GATEWAY);
    assertEquals(
        responseEntity.getBody().getFaultCodeCategory(), FaultCategoryDto.PAYMENT_UNAVAILABLE);
    assertEquals(
        responseEntity.getBody().getFaultCodeDetail().getValue(),
        PartyConfigurationFaultDto.PPT_DOMINIO_DISABILITATO.getValue());
  }

  @Test
  void shouldReturnResponseEntityWithValidationFault()
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

    Method method =
        PaymentRequestsController.class.getDeclaredMethod(
            "nodoErrorHandler", NodoErrorException.class);
    method.setAccessible(true);

    ResponseEntity<ValidationFaultPaymentProblemJsonDto> responseEntity =
        (ResponseEntity<ValidationFaultPaymentProblemJsonDto>)
            method.invoke(
                paymentRequestsController,
                new NodoErrorException(ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue()));

    assertEquals(responseEntity != null, Boolean.TRUE);
    assertEquals(responseEntity.getStatusCode(), HttpStatus.NOT_FOUND);
    assertEquals(responseEntity.getBody().getFaultCodeCategory(), FaultCategoryDto.PAYMENT_UNKNOWN);
    assertEquals(
        responseEntity.getBody().getFaultCodeDetail().getValue(),
        ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue());
  }

  @Test
  void shouldReturnResponseEntityWithGatewayFault()
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

    Method method =
        PaymentRequestsController.class.getDeclaredMethod(
            "nodoErrorHandler", NodoErrorException.class);
    method.setAccessible(true);

    ResponseEntity<GatewayFaultPaymentProblemJsonDto> responseEntity =
        (ResponseEntity<GatewayFaultPaymentProblemJsonDto>)
            method.invoke(
                paymentRequestsController,
                new NodoErrorException(GatewayFaultDto.PAA_SYSTEM_ERROR.getValue()));

    assertEquals(responseEntity != null, Boolean.TRUE);
    assertEquals(responseEntity.getStatusCode(), HttpStatus.BAD_GATEWAY);
    assertEquals(responseEntity.getBody().getFaultCodeCategory(), FaultCategoryDto.GENERIC_ERROR);
    assertEquals(
        responseEntity.getBody().getFaultCodeDetail().getValue(),
        GatewayFaultDto.PAA_SYSTEM_ERROR.getValue());
  }

  @Test
  void shouldReturnResponseEntityWithPartyTimeoutFault()
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

    Method method =
        PaymentRequestsController.class.getDeclaredMethod(
            "nodoErrorHandler", NodoErrorException.class);
    method.setAccessible(true);

    ResponseEntity<PartyTimeoutFaultPaymentProblemJsonDto> responseEntity =
        (ResponseEntity<PartyTimeoutFaultPaymentProblemJsonDto>)
            method.invoke(
                paymentRequestsController,
                new NodoErrorException(
                    PartyTimeoutFaultDto.PPT_STAZIONE_INT_PA_IRRAGGIUNGIBILE.getValue()));

    assertEquals(responseEntity != null, Boolean.TRUE);
    assertEquals(responseEntity.getStatusCode(), HttpStatus.GATEWAY_TIMEOUT);
    assertEquals(responseEntity.getBody().getFaultCodeCategory(), FaultCategoryDto.GENERIC_ERROR);
    assertEquals(
        responseEntity.getBody().getFaultCodeDetail().getValue(),
        PartyTimeoutFaultDto.PPT_STAZIONE_INT_PA_IRRAGGIUNGIBILE.getValue());
  }

  @Test
  void shouldReturnResponseEntityWithPaymentStatusFault()
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

    Method method =
        PaymentRequestsController.class.getDeclaredMethod(
            "nodoErrorHandler", NodoErrorException.class);
    method.setAccessible(true);

    ResponseEntity<PaymentStatusFaultPaymentProblemJsonDto> responseEntity =
        (ResponseEntity<PaymentStatusFaultPaymentProblemJsonDto>)
            method.invoke(
                paymentRequestsController,
                new NodoErrorException(PaymentStatusFaultDto.PAA_PAGAMENTO_IN_CORSO.getValue()));

    assertEquals(responseEntity != null, Boolean.TRUE);
    assertEquals(responseEntity.getStatusCode(), HttpStatus.CONFLICT);
    assertEquals(
        responseEntity.getBody().getFaultCodeCategory(), FaultCategoryDto.PAYMENT_UNAVAILABLE);
    assertEquals(
        responseEntity.getBody().getFaultCodeDetail().getValue(),
        PaymentStatusFaultDto.PAA_PAGAMENTO_IN_CORSO.getValue());
  }
}
