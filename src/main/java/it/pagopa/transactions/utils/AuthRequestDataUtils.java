package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.annotations.ValueObject;
import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AuthRequestDataUtils {

    @ValueObject
    public record AuthRequestData(
            String authorizationCode,
            String outcome,
            String rrn
    ) {

    }

    public AuthRequestData from(UpdateAuthorizationRequestDto updateAuthorizationRequest) {
        AuthRequestData result;
        switch (updateAuthorizationRequest.getOutcomeGateway()) {
            case OutcomeVposGatewayDto t -> {
                result = new AuthRequestData(t.getAuthorizationCode(),t.getOutcome().toString(),t.getRrn());
            }
            case OutcomeXpayGatewayDto t -> {
                result = new AuthRequestData(t.getAuthorizationCode(),t.getOutcome().toString(), null);
            }
            default ->
                    throw new InvalidRequestException("Unexpected value: " + updateAuthorizationRequest.getOutcomeGateway());
        }

        return result;
    }
}
