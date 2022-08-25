package it.pagopa.transactions.configurations;

import com.fasterxml.jackson.core.JsonProcessingException;
import it.pagopa.generated.nodoperpsp.model.NodoVerificaRPT;
import it.pagopa.generated.transactions.model.ObjectFactory;
import it.pagopa.generated.transactions.model.VerifyPaymentNoticeReq;
import it.pagopa.transactions.utils.soap.Jaxb2SoapEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;

import javax.xml.bind.Marshaller;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class NodoConfigTest {

  @InjectMocks private NodoConfig nodoConfig;

  private final String nodoConnectionString =
      "{\"idPSP\":\"idPsp\",\"idChannel\":\"idChannel\",\"idBrokerPSP\":\"idBrokerPsp\",\"password\":\"password\"}";

  @Test
  void shouldReturnValidVerificaRPTBaseRequest()
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException,
          JsonProcessingException {

    NodoVerificaRPT nodoVerificaRPT =
        nodoConfig.baseNodoVerificaRPTRequest(
            nodoConnectionString, new it.pagopa.generated.nodoperpsp.model.ObjectFactory());
    assertEquals(nodoVerificaRPT != null, Boolean.TRUE);
  }

  @Test
  void shouldReturnValidVerifyPaymentNoticeBaseRequest()
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException,
          JsonProcessingException {

    VerifyPaymentNoticeReq verifyPaymentNoticeReq =
        nodoConfig.baseVerifyPaymentNoticeReq(nodoConnectionString, new ObjectFactory());
    assertEquals(verifyPaymentNoticeReq != null, Boolean.TRUE);
  }
}
