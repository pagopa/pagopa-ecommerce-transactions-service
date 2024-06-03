package it.pagopa.transactions.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.UNPROCESSABLE_ENTITY)
@Getter
public class NpgNotRetryableErrorException extends RuntimeException {
    private final String detail;

    private final HttpStatus httpStatus;

    public NpgNotRetryableErrorException(
            @NonNull String detail,
            @NonNull HttpStatus httpStatus
    ) {
        super(detail.concat(", HTTP status code: [%s]".formatted(httpStatus.value())));
        this.detail = detail;
        this.httpStatus = httpStatus;
    }
}
