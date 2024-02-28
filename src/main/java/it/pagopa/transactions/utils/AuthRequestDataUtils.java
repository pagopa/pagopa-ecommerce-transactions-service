package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AuthRequestDataUtils {

    private final UUIDUtils uuidUtils;

    public static final String OUTCOME_OK = "OK";

    public static final String OUTCOME_KO = "KO";

    @Autowired
    public AuthRequestDataUtils(
            UUIDUtils uuidUtils
    ) {
        this.uuidUtils = uuidUtils;
    }

    public record AuthRequestData(
            String authorizationCode,
            String outcome,
            String rrn,
            String errorCode
    ) {

    }

    public AuthRequestData from(UpdateAuthorizationRequestDto updateAuthorizationRequest, TransactionId transactionId) {

        return switch (updateAuthorizationRequest.getOutcomeGateway()) {
            case OutcomeVposGatewayDto t ->
                    new AuthRequestData(t.getAuthorizationCode(), t.getOutcome().toString(), t.getRrn(), t.getErrorCode() != null ? t.getErrorCode().getValue() : null);
            case OutcomeXpayGatewayDto t ->
                    new AuthRequestData(t.getAuthorizationCode(), t.getOutcome().toString(), uuidUtils.uuidToBase64(transactionId.uuid()), t.getErrorCode() != null ? t.getErrorCode().getValue().toString() : null);
            case OutcomeNpgGatewayDto t ->
                    new AuthRequestData(t.getAuthorizationCode(), npgResultToOutcome(t.getOperationResult()), t.getRrn(), t.getErrorCode());
            case OutcomeRedirectGatewayDto t ->
                    new AuthRequestData(t.getAuthorizationCode(), redirectResultToOutcome(t.getOutcome()), null, t.getErrorCode());
            default ->
                    throw new InvalidRequestException("Unexpected value: " + updateAuthorizationRequest.getOutcomeGateway());
        };

    }

    private String npgResultToOutcome(OutcomeNpgGatewayDto.OperationResultEnum result) {
        String outcome = OUTCOME_KO;
        if (result.equals(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)) {
            outcome = OUTCOME_OK;
        }
        log.info("NPG operation result: {} mapped to outcome -> {}", result, outcome);
        return outcome;
    }

    private String redirectResultToOutcome(AuthorizationOutcomeDto authorizationOutcomeDto) {
        String outcome = OUTCOME_KO;
        if (authorizationOutcomeDto.equals(AuthorizationOutcomeDto.OK)) {
            outcome = OUTCOME_OK;
        }
        log.info("Redirect authorization outcome: {} mapped to outcome -> {}", authorizationOutcomeDto, outcome);
        return outcome;
    }
}
