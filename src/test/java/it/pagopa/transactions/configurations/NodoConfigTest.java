package it.pagopa.transactions.configurations;

import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.VerifyPaymentNoticeReq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class NodoConfigTest {

    @InjectMocks
    private NodoConfig nodoConfig;

    @Test
    void shouldReturnValidVerifyPaymentNoticeBaseRequest() {
        ReflectionTestUtils.setField(nodoConfig, "nodoConnectionParamsAsString", "{}");
        ReflectionTestUtils.setField(
                nodoConfig,
                "objectFactoryNodeForPsp",
                new it.pagopa.generated.transactions.model.ObjectFactory()
        );
        VerifyPaymentNoticeReq verifyPaymentNoticeReq = nodoConfig
                .baseVerifyPaymentNoticeReq();
        assertEquals(Boolean.TRUE, verifyPaymentNoticeReq != null);
    }

    @Test
    void shouldReturnValidActivatePaymentNoticeReqRPTRequest() {
        ReflectionTestUtils.setField(nodoConfig, "nodoConnectionParamsAsString", "{}");
        ReflectionTestUtils.setField(
                nodoConfig,
                "objectFactoryNodeForPsp",
                new it.pagopa.generated.transactions.model.ObjectFactory()
        );
        ActivatePaymentNoticeReq request = nodoConfig
                .baseActivatePaymentNoticeReq();
        assertEquals(Boolean.TRUE, request != null);
    }
}
