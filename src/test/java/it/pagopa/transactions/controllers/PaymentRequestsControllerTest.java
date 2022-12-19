package it.pagopa.transactions.controllers;

import it.pagopa.generated.nodoperpsp.model.FaultBean;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRequestsControllerTest {

    @InjectMocks
    private PaymentRequestsController paymentRequestsController;

    @Mock
    private PaymentRequestsService paymentRequestsService;

    private static FaultBean faultBeanWithCode(String faultCode) {
        FaultBean fault = new FaultBean();
        fault.setFaultCode(faultCode);

        return fault;
    }

    @Test
    void shouldGetPaymentInfoGivenValidRptid() {
        String RPTID = "77777777777302016723749670035";

        PaymentRequestsGetResponseDto response = new PaymentRequestsGetResponseDto();
        response.setRptId(RPTID);
        response.amount(1000);
        response.setDescription("Payment test");
        when(paymentRequestsService.getPaymentRequestInfo(RPTID)).thenReturn(Mono.just(response));

        ResponseEntity<PaymentRequestsGetResponseDto> responseEntity = paymentRequestsController
                .getPaymentRequestInfo(RPTID, null).block();

        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
    }

    @Test
    void shouldGenericBadGatewayResponse()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = PaymentRequestsController.class.getDeclaredMethod(
                "genericBadGatewayHandler",
                RuntimeException.class
        );
        method.setAccessible(true);

        ResponseEntity<ProblemJsonDto> responseEntity = (ResponseEntity<ProblemJsonDto>) method
                .invoke(paymentRequestsController, new RuntimeException());

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
    }

    @Test
    void shouldReturnResponseEntityWithPartyConfigurationFault()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode(PartyConfigurationFaultDto.PPT_DOMINIO_DISABILITATO.getValue());

        Method method = PaymentRequestsController.class.getDeclaredMethod(
                "nodoErrorHandler",
                NodoErrorException.class
        );
        method.setAccessible(true);

        ResponseEntity<PartyConfigurationFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<PartyConfigurationFaultPaymentProblemJsonDto>) method
                .invoke(
                        paymentRequestsController,
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
        assertEquals(
                FaultCategoryDto.PAYMENT_UNAVAILABLE,
                responseEntity.getBody().getFaultCodeCategory()
        );
        assertEquals(
                PartyConfigurationFaultDto.PPT_DOMINIO_DISABILITATO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithValidationFault()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode(ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue());

        Method method = PaymentRequestsController.class.getDeclaredMethod(
                "nodoErrorHandler",
                NodoErrorException.class
        );
        method.setAccessible(true);

        ResponseEntity<ValidationFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<ValidationFaultPaymentProblemJsonDto>) method
                .invoke(
                        paymentRequestsController,
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.NOT_FOUND, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.PAYMENT_UNKNOWN, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithGatewayFault()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode(GatewayFaultDto.PAA_SYSTEM_ERROR.getValue());

        Method method = PaymentRequestsController.class.getDeclaredMethod(
                "nodoErrorHandler",
                NodoErrorException.class
        );
        method.setAccessible(true);

        ResponseEntity<GatewayFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<GatewayFaultPaymentProblemJsonDto>) method
                .invoke(
                        paymentRequestsController,
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.GENERIC_ERROR, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                GatewayFaultDto.PAA_SYSTEM_ERROR.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithPartyTimeoutFault()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode(PartyTimeoutFaultDto.PPT_STAZIONE_INT_PA_IRRAGGIUNGIBILE.getValue());

        Method method = PaymentRequestsController.class.getDeclaredMethod(
                "nodoErrorHandler",
                NodoErrorException.class
        );
        method.setAccessible(true);

        ResponseEntity<PartyTimeoutFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<PartyTimeoutFaultPaymentProblemJsonDto>) method
                .invoke(
                        paymentRequestsController,
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.GENERIC_ERROR, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                PartyTimeoutFaultDto.PPT_STAZIONE_INT_PA_IRRAGGIUNGIBILE.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithPaymentStatusFault()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode(PaymentStatusFaultDto.PAA_PAGAMENTO_IN_CORSO.getValue());

        Method method = PaymentRequestsController.class.getDeclaredMethod(
                "nodoErrorHandler",
                NodoErrorException.class
        );
        method.setAccessible(true);

        ResponseEntity<PaymentStatusFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<PaymentStatusFaultPaymentProblemJsonDto>) method
                .invoke(
                        paymentRequestsController,
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.CONFLICT, responseEntity.getStatusCode());
        assertEquals(
                FaultCategoryDto.PAYMENT_UNAVAILABLE,
                responseEntity.getBody().getFaultCodeCategory()
        );
        assertEquals(
                PaymentStatusFaultDto.PAA_PAGAMENTO_IN_CORSO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithGenericGatewayFault()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        FaultBean faultBean = faultBeanWithCode("UNKNOWN_ERROR");

        Method method = PaymentRequestsController.class.getDeclaredMethod(
                "nodoErrorHandler",
                NodoErrorException.class
        );
        method.setAccessible(true);

        ResponseEntity<ProblemJsonDto> responseEntity = (ResponseEntity<ProblemJsonDto>) method
                .invoke(paymentRequestsController, new NodoErrorException(faultBean));

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
    }
}
