package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.RptId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.CONFLICT)
public class AlreadyProcessedException extends Exception {
    private final RptId rptId;

    public AlreadyProcessedException(RptId rptId) {
        this.rptId = rptId;
    }

    public RptId getRptId() {
        return rptId;
    }
}
