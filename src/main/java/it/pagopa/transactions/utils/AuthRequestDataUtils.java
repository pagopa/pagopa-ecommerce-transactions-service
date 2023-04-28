package it.pagopa.transactions.utils;

import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AuthRequestDataUtils {

    @AllArgsConstructor
    @NoArgsConstructor
    public static class DataAuthRequest {
        public String authorizationCode;
        public String rrn;
        public String outcome;
    }

    public DataAuthRequest extract(UpdateAuthorizationRequestDto updateAuthorizationRequest) {
        DataAuthRequest result = new DataAuthRequest();
        switch (updateAuthorizationRequest.getOutcomeGateway()) {
            case OutcomeVposGatewayDto t -> {
                result.outcome = t.getOutcome().toString();
                result.authorizationCode = t.getAuthorizationCode();
                result.rrn = t.getRrn();
            }
            case OutcomeXpayGatewayDto t -> {
                result.outcome = t.getOutcome().toString();
                result.authorizationCode = t.getAuthorizationCode();
            }
            default ->
                    throw new IllegalStateException("Unexpected value: " + updateAuthorizationRequest.getOutcomeGateway());
        }

        return result;
    }
}
