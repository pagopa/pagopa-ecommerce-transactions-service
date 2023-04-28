package it.pagopa.transactions.utils;

import io.vavr.control.Either;
import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
public class AuthRequestDataUtilsTest {

    @InjectMocks
    AuthRequestDataUtils authRequestDataUtils;

    @Test
    void shouldExtractVposInformation() {
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                                .rrn("rrn")
                )
                .timestampOperation(OffsetDateTime.now());
        AuthRequestDataUtils.DataAuthRequest data = authRequestDataUtils.extract(updateAuthorizationRequest);

        assertEquals(data.authorizationCode, ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getAuthorizationCode());
        assertEquals(data.rrn, ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getRrn());
        assertEquals(data.outcome, ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getOutcome().toString());
    }

    @Test
    void shouldExtractXpayInformation() {
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());
        AuthRequestDataUtils.DataAuthRequest data = authRequestDataUtils.extract(updateAuthorizationRequest);

        assertEquals(data.authorizationCode, ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getAuthorizationCode());
        assertEquals(data.outcome, ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getOutcome().toString());
    }

}
