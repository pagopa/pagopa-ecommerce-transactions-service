package it.pagopa.transactions.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import javax.xml.bind.JAXBElement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import it.pagopa.nodeforpsp.ActivatePaymentNoticeReq;
import it.pagopa.nodeforpsp.ActivatePaymentNoticeRes;
import it.pagopa.nodeforpsp.CtFaultBean;
import it.pagopa.nodeforpsp.CtQrCode;
import it.pagopa.nodeforpsp.ObjectFactory;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.client.core.WebServiceTemplate;

@ExtendWith(MockitoExtension.class)
public class NodeForPspClientTest {

        @InjectMocks
        private NodeForPspClient client;

        @Mock
        private WebServiceTemplate webServiceTemplate;

        @Test
        void shouldReturnActivatePaymentResponseGivenValidPaymentNoticeTest() {

                /**
                 * preconditions
                 */
                ObjectFactory objectFactory = new ObjectFactory();
                BigDecimal amount = BigDecimal.valueOf(1200);
                String fiscalCode = "77777777777";
                String paymentNotice = "302000100000009424";
                String paymentToken = UUID.randomUUID().toString();

                ActivatePaymentNoticeReq request = objectFactory.createActivatePaymentNoticeReq();
                CtQrCode qrCode = new CtQrCode();
                qrCode.setFiscalCode(fiscalCode);
                qrCode.setNoticeNumber(paymentNotice);
                request.setAmount(amount);
                request.setQrCode(qrCode);
                JAXBElement<ActivatePaymentNoticeReq> jaxbElementRequest = objectFactory
                                .createActivatePaymentNoticeReq(request);

                ActivatePaymentNoticeRes activatePaymentRes = objectFactory.createActivatePaymentNoticeRes();
                activatePaymentRes.setPaymentToken(paymentToken);
                activatePaymentRes.setFiscalCodePA(fiscalCode);
                activatePaymentRes.setTotalAmount(amount);

                when(webServiceTemplate.marshalSendAndReceive(eq(jaxbElementRequest),
                                any(WebServiceMessageCallback.class))).thenReturn(activatePaymentRes);

                /**
                 * test
                 */
                ActivatePaymentNoticeRes testResponse = client.activatePaymentNotice(jaxbElementRequest);

                /**
                 * asserts
                 */
                assertThat(testResponse.getPaymentToken()).isEqualTo(paymentToken);
                assertThat(testResponse.getFiscalCodePA()).isEqualTo(fiscalCode);
                assertThat(testResponse.getTotalAmount()).isEqualTo(amount);
        }

        @Test
        void shouldReturnFaultGivenDuplicatePaymentNoticeTest() {

                /**
                 * preconditions
                 */
                ObjectFactory objectFactory = new ObjectFactory();
                BigDecimal amount = BigDecimal.valueOf(1200);
                String fiscalCode = "77777777777";
                String paymentNotice = "30200010000000999";
                String faultError = "PAA_PAGAMENTO_DUPLICATO";

                ActivatePaymentNoticeReq request = objectFactory.createActivatePaymentNoticeReq();
                CtQrCode qrCode = new CtQrCode();
                qrCode.setFiscalCode(fiscalCode);
                qrCode.setNoticeNumber(paymentNotice);
                request.setAmount(amount);
                request.setQrCode(qrCode);
                JAXBElement<ActivatePaymentNoticeReq> jaxbElementRequest = objectFactory
                                .createActivatePaymentNoticeReq(request);

                ActivatePaymentNoticeRes activatePaymentRes = objectFactory.createActivatePaymentNoticeRes();
                CtFaultBean fault = objectFactory.createCtFaultBean();
                fault.setFaultCode(faultError);
                fault.setFaultString(faultError);
                activatePaymentRes.setFault(fault);

                when(webServiceTemplate.marshalSendAndReceive(eq(jaxbElementRequest),
                                any(WebServiceMessageCallback.class))).thenReturn(activatePaymentRes);

                /**
                 * test
                 */
                ActivatePaymentNoticeRes testResponse = client.activatePaymentNotice(jaxbElementRequest);

                /**
                 * asserts
                 */
                assertThat(testResponse.getFault().getFaultCode()).isEqualTo(faultError);
                assertThat(testResponse.getFault().getFaultString()).isEqualTo(faultError);
        }
}
