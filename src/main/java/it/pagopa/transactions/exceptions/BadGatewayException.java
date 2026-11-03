package it.pagopa.transactions.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Optional;

@ResponseStatus(value = HttpStatus.BAD_GATEWAY)
@Getter
public class BadGatewayException extends RuntimeException {
    private final String detail;

    private final HttpStatus httpStatus;

    private final String downstreamHttpStatus;

    private final String downstreamResponseBody;

    public BadGatewayException(
            String detail,
            HttpStatus httpStatus
    ) {
        this(detail, httpStatus, null, null);
    }

    public BadGatewayException(
            String detail,
            HttpStatus httpStatus,
            String downstreamHttpStatus,
            String downstreamResponseBody
    ) {
        super(detail.concat(", HTTP status code: %s".formatted(httpStatus)));
        this.detail = detail;
        this.httpStatus = httpStatus;
        this.downstreamHttpStatus = downstreamHttpStatus;
        this.downstreamResponseBody = downstreamResponseBody;
    }

    public BadGatewayException(
            String detail,
            Throwable cause,
            HttpStatus httpStatus
    ) {
        this(detail, cause, httpStatus, null, null);
    }

    public BadGatewayException(
            String detail,
            Throwable cause,
            HttpStatus httpStatus,
            String downstreamHttpStatus,
            String downstreamResponseBody
    ) {
        super(detail, cause);
        this.detail = detail;
        this.httpStatus = httpStatus;
        this.downstreamHttpStatus = downstreamHttpStatus;
        this.downstreamResponseBody = downstreamResponseBody;
    }

    public Optional<String> downstreamHttpStatus() {
        return Optional.ofNullable(downstreamHttpStatus);
    }

    public Optional<String> downstreamResponseBody() {
        return Optional.ofNullable(downstreamResponseBody);
    }

}
