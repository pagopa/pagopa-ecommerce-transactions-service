package it.pagopa.transactions.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;
import org.springframework.lang.Nullable;

@Getter
public class WalletErrorResponseException extends RuntimeException {
    private final String detail;

    private final HttpStatusCode httpStatus;

    public WalletErrorResponseException(
            String detail,
            @Nullable HttpStatusCode httpStatus,
            @Nullable Throwable cause
    ) {
        super(detail.concat(", HTTP status code: %s".formatted(httpStatus)), cause);
        this.detail = detail;
        this.httpStatus = httpStatus;
    }

}
