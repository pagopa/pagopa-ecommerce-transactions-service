package it.pagopa.transactions.exceptions;

import lombok.Getter;

@Getter
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
