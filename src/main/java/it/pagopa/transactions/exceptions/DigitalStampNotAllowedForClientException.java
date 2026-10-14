package it.pagopa.transactions.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@Getter
@ResponseStatus(value = HttpStatus.NOT_FOUND)
public class DigitalStampNotAllowedForClientException extends RuntimeException {

    private final String clientId;

    public DigitalStampNotAllowedForClientException(String clientId) {
        super(
                "Digital stamp payments are only allowed from EC frontend. Client: "
                        .concat(clientId)
        );
        this.clientId = clientId;
    }
}
