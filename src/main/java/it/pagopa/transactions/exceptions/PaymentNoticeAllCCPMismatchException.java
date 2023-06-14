package it.pagopa.transactions.exceptions;

import lombok.Getter;

@Getter
public class PaymentNoticeAllCCPMismatchException extends RuntimeException {

    private final String rptId;

    private final Boolean requestAllCCP;

    private final Boolean paymentNoticeAllCCP;

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
