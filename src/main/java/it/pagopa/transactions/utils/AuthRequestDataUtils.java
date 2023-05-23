package it.pagopa.transactions.utils;

import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AuthRequestDataUtils {

    public record AuthRequestData(
            String authorizationCode,
            String outcome,
            String rrn,
            String errorCode
    ) {

    }

    public AuthRequestData from(UpdateAuthorizationRequestDto updateAuthorizationRequest) {
        AuthRequestData result;
        switch (updateAuthorizationRequest.getOutcomeGateway()) {
            case OutcomeVposGatewayDto t -> {
                result = new AuthRequestData(t.getAuthorizationCode(),t.getOutcome().toString(),t.getRrn(), t.getErrorCode() != null ? t.getErrorCode().getValue() : null);
            }
            case OutcomeXpayGatewayDto t -> {
                result = new AuthRequestData(t.getAuthorizationCode(),t.getOutcome().toString(), null, t.getErrorCode() != null ? t.getErrorCode().getValue().toString() : null);
            }
            default ->
                    throw new InvalidRequestException("Unexpected value: " + updateAuthorizationRequest.getOutcomeGateway());
        }

        return result;
    }
}
