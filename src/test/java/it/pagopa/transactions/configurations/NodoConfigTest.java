package it.pagopa.transactions.configurations;

import it.pagopa.generated.nodoperpsp.model.NodoVerificaRPT;
import it.pagopa.generated.transactions.model.VerifyPaymentNoticeReq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class NodoConfigTest {

    @InjectMocks
    private NodoConfig nodoConfig;

    @Test
    void shouldReturnValidVerificaRPTBaseRequest() {
        NodoVerificaRPT nodoVerificaRPT = nodoConfig.baseNodoVerificaRPTRequest();
        assertEquals(Boolean.TRUE, nodoVerificaRPT != null);
    }

    @Test
    void shouldReturnValidVerifyPaymentNoticeBaseRequest() {
        VerifyPaymentNoticeReq verifyPaymentNoticeReq = nodoConfig
                .baseVerifyPaymentNoticeReq();
        assertEquals(Boolean.TRUE, verifyPaymentNoticeReq != null);
    }
}
