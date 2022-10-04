package it.pagopa.transactions.utils;

import it.pagopa.generated.nodoperpsp.model.NodoTipoCodiceIdRPT;
import it.pagopa.generated.nodoperpsp.model.ObjectFactory;
import it.pagopa.transactions.domain.RptId;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodoUtilitiesTest {

    @Mock
    it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp;

    @InjectMocks
    NodoUtilities nodoUtilities;

    @Test
    void shouldGetNodoTipoCodiceIdRPTAux0() {
        ObjectFactory objectFactory = new ObjectFactory();
        RptId rptId = new RptId("77777777777011222222222222222");

        Mockito.when(objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT()).thenReturn(objectFactory.createNodoTipoCodiceIdRPT());

        NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = nodoUtilities.getCodiceIdRpt(rptId);

        Assert.assertNotNull(nodoTipoCodiceIdRPT.getQrCode());
        Assert.assertEquals("11", nodoTipoCodiceIdRPT.getQrCode().getCodStazPA());
        Assert.assertEquals("0",nodoTipoCodiceIdRPT.getQrCode().getAuxDigit());
        Assert.assertEquals("77777777777",nodoTipoCodiceIdRPT.getQrCode().getCF());
        Assert.assertEquals("222222222222222",nodoTipoCodiceIdRPT.getQrCode().getCodIUV());
    }

    @Test
    void shouldGetNodoTipoCodiceIdRPTAux1() {
        ObjectFactory objectFactory = new ObjectFactory();
        RptId rptId = new RptId("77777777777122222222222222222");

        Mockito.when(objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT()).thenReturn(objectFactory.createNodoTipoCodiceIdRPT());

        NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = nodoUtilities.getCodiceIdRpt(rptId);

        Assert.assertNotNull(nodoTipoCodiceIdRPT.getQrCode());
        Assert.assertNull(nodoTipoCodiceIdRPT.getQrCode().getCodStazPA());
        Assert.assertEquals("1",nodoTipoCodiceIdRPT.getQrCode().getAuxDigit());
        Assert.assertEquals("77777777777",nodoTipoCodiceIdRPT.getQrCode().getCF());
        Assert.assertEquals("22222222222222222",nodoTipoCodiceIdRPT.getQrCode().getCodIUV());
    }

    @Test
    void shouldGetNodoTipoCodiceIdRPTAux2() {
        ObjectFactory objectFactory = new ObjectFactory();
        RptId rptId = new RptId("77777777777211111111111111111");

        Mockito.when(objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT()).thenReturn(objectFactory.createNodoTipoCodiceIdRPT());

        NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = nodoUtilities.getCodiceIdRpt(rptId);

        Assert.assertNotNull(nodoTipoCodiceIdRPT.getQrCode());
        Assert.assertNull(nodoTipoCodiceIdRPT.getQrCode().getCodStazPA());
        Assert.assertEquals("2",nodoTipoCodiceIdRPT.getQrCode().getAuxDigit());
        Assert.assertEquals("77777777777",nodoTipoCodiceIdRPT.getQrCode().getCF());
        Assert.assertEquals("11111111111111111",nodoTipoCodiceIdRPT.getQrCode().getCodIUV());
    }

    @Test
    void shouldGetNodoTipoCodiceIdRPTAux3() {
        ObjectFactory objectFactory = new ObjectFactory();
        RptId rptId = new RptId("77777777777311111111111111111");

        Mockito.when(objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT()).thenReturn(objectFactory.createNodoTipoCodiceIdRPT());

        NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = nodoUtilities.getCodiceIdRpt(rptId);

        Assert.assertNotNull(nodoTipoCodiceIdRPT.getQrCode());
        Assert.assertNull(nodoTipoCodiceIdRPT.getQrCode().getCodStazPA());
        Assert.assertEquals("3",nodoTipoCodiceIdRPT.getQrCode().getAuxDigit());
        Assert.assertEquals("77777777777",nodoTipoCodiceIdRPT.getQrCode().getCF());
        Assert.assertEquals("11111111111111111",nodoTipoCodiceIdRPT.getQrCode().getCodIUV());
    }

}
