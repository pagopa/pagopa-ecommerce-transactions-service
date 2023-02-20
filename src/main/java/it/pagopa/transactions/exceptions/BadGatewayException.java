package it.pagopa.transactions.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.BAD_GATEWAY)
@Getter
public class BadGatewayException extends RuntimeException {
    private final String detail;

    private final HttpStatus httpStatus;

    public BadGatewayException(
            String detail,
            HttpStatus httpStatus
    ) {
        super(detail);
        this.detail = detail;
        this.httpStatus = httpStatus;
    }

    public BadGatewayException(
            String detail,
            Throwable cause,
            HttpStatus httpStatus
    ) {
        super(detail, cause);
        this.detail = detail;
        this.httpStatus = httpStatus;
    }

}
