package it.pagopa.transactions.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.BAD_GATEWAY)
public class BadGatewayException extends RuntimeException {
    private final String detail;

    public BadGatewayException(String detail) {
        super(detail);
        this.detail = detail;
    }

    public BadGatewayException(
            String detail,
            Throwable cause
    ) {
        super(detail, cause);
        this.detail = detail;
    }

    public String getDetail() {
        return detail;
    }
}
