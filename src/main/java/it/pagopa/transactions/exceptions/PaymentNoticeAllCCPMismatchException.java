package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.v1.RptId;
import lombok.Getter;

@Getter
public class PaymentNoticeAllCCPMismatchException extends RuntimeException {

    private String rptId;

    private Integer requestAllCCP;

    private Integer transactionAllCCP;

    public PaymentNoticeAllCCPMismatchException(
            String rptId,
            Integer requestAllCCP,
            Integer transactionAllCCP
    ) {
        super("Payment notice allCCP mismatch");
        this.rptId = rptId;
        this.requestAllCCP = requestAllCCP;
        this.transactionAllCCP = transactionAllCCP;
    }

}
