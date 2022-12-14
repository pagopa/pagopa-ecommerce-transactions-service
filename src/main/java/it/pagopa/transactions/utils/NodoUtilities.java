package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.generated.nodoperpsp.model.NodoTipoCodiceIdRPT;
import it.pagopa.generated.nodoperpsp.model.QrCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NodoUtilities {

    @Autowired
    it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp;
    /**
     * Define a nodoTipoCodiceIdRPT object to send to PagoPA Services, containing payment information
     * Ask the pagopa service administrator or read documentation from RptId definition
     *
     * @param {RptId} rptId - Payment information provided by BackendApp
     * @return {nodoTipoCodiceIdRPT} The result generated for PagoPa
     */
    public NodoTipoCodiceIdRPT getCodiceIdRpt(RptId rptId) {
        NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT();
        QrCode qrCode = new QrCode();
        qrCode.setCF(rptId.getFiscalCode());
        qrCode.setAuxDigit(rptId.getAuxDigit());
        qrCode.setCodIUV(rptId.getIUV());
        if(auxDigitZero(rptId.getAuxDigit())) {
            qrCode.setCodStazPA(rptId.getApplicationCode());
        }
        nodoTipoCodiceIdRPT.setQrCode(qrCode);
        return nodoTipoCodiceIdRPT;
    }

    private boolean auxDigitZero(String auxDigit) {
        return "0".equals(auxDigit);
    }

}
