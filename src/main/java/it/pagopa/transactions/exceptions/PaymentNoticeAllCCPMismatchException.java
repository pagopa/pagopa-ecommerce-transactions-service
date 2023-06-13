package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.v1.RptId;
import lombok.Getter;

@Getter
public class PaymentNoticeAllCCPMismatchException extends RuntimeException {

    private String rptId;

    private Boolean requestAllCCP;

    private Boolean paymentNoticeAllCCP;

    public PaymentNoticeAllCCPMismatchException(
            String rptId,
            Boolean requestAllCCP,
            Boolean paymentNoticeAllCCP
    ) {
        super("Payment notice allCCP mismatch");
        this.rptId = rptId;
        this.requestAllCCP = requestAllCCP;
        this.paymentNoticeAllCCP = paymentNoticeAllCCP;
    }

}
