package it.pagopa.transactions.exceptions;

import it.pagopa.transactions.domain.RptId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.CONFLICT)
public class AlreadyAuthorizedException extends Exception {
    private final RptId rptId;

    public AlreadyAuthorizedException(RptId rptId) {
        this.rptId = rptId;
    }

    public RptId getRptId() {
        return rptId;
    }
}
