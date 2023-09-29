package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
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
        AuthRequestData result;
        switch (updateAuthorizationRequest.getOutcomeGateway()) {
            case OutcomeVposGatewayDto t -> {
                result = new AuthRequestData(t.getAuthorizationCode(), t.getOutcome().toString(), t.getRrn(), t.getErrorCode() != null ? t.getErrorCode().getValue() : null);
            }
            case OutcomeXpayGatewayDto t -> {
                result = new AuthRequestData(t.getAuthorizationCode(), t.getOutcome().toString(), uuidUtils.uuidToBase64(transactionId.uuid()), t.getErrorCode() != null ? t.getErrorCode().getValue().toString() : null);
            }
            case OutcomeNpgGatewayDto t -> {
                result = new AuthRequestData(t.getAuthorizationCode(), npgResultToOutcome(t.getOperationResult()), t.getPaymentEndToEndId(), null);
            }
            default ->
                    throw new InvalidRequestException("Unexpected value: " + updateAuthorizationRequest.getOutcomeGateway());
        }

        return result;
    }

    private String npgResultToOutcome(OutcomeNpgGatewayDto.OperationResultEnum result) {
        String outcome = OUTCOME_KO;
        if (result.equals(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)) {
            outcome = OUTCOME_OK;
        }
        log.info("NPG operation result: {} outcome -> {}", result, outcome);
        return outcome;
    }
}
