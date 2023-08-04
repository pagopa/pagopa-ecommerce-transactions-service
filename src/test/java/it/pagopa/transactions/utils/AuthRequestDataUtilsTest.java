package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.v1.TransactionId;
import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class AuthRequestDataUtilsTest {

    @InjectMocks
    private AuthRequestDataUtils authRequestDataUtils;
    @Mock
    private UUIDUtils uuidUtils;

    private static final String TRANSACTION_ID_ENCODED = "transactionIdEncoded";

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
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        AuthRequestDataUtils.AuthRequestData data = authRequestDataUtils
                .from(updateAuthorizationRequest, transactionId);

        assertEquals(
                data.authorizationCode(),
                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getAuthorizationCode()
        );
        assertEquals(data.rrn(), ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getRrn());
        assertEquals(
                data.outcome(),
                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getOutcome().toString()
        );
    }

    @Test
    void shouldExtractVposInformationWithErrorCode() {
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.OK)
                                .errorCode(OutcomeVposGatewayDto.ErrorCodeEnum._01)
                )
                .timestampOperation(OffsetDateTime.now());
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        AuthRequestDataUtils.AuthRequestData data = authRequestDataUtils
                .from(updateAuthorizationRequest, transactionId);

        assertEquals(
                data.authorizationCode(),
                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getAuthorizationCode()
        );
        assertEquals(data.rrn(), ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getRrn());
        assertEquals(
                data.outcome(),
                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getOutcome().toString()
        );
        assertEquals(
                data.errorCode(),
                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getErrorCode().toString()
        );
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

        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        Mockito.when(uuidUtils.uuidToBase64(transactionId.uuid())).thenReturn(TRANSACTION_ID_ENCODED);
        AuthRequestDataUtils.AuthRequestData data = authRequestDataUtils
                .from(updateAuthorizationRequest, transactionId);

        assertEquals(data.rrn(), TRANSACTION_ID_ENCODED);
        assertEquals(
                data.authorizationCode(),
                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getAuthorizationCode()
        );
        assertEquals(
                data.outcome(),
                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getOutcome().toString()
        );
    }

    @Test
    void shouldExtractXpayInformationWithErrorCode() {
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .errorCode(OutcomeXpayGatewayDto.ErrorCodeEnum.NUMBER_1)
                )
                .timestampOperation(OffsetDateTime.now());
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        Mockito.when(uuidUtils.uuidToBase64(transactionId.uuid())).thenReturn(TRANSACTION_ID_ENCODED);
        AuthRequestDataUtils.AuthRequestData data = authRequestDataUtils
                .from(updateAuthorizationRequest, transactionId);

        assertEquals(data.rrn(), TRANSACTION_ID_ENCODED);
        assertEquals(
                data.authorizationCode(),
                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getAuthorizationCode()
        );
        assertEquals(
                data.outcome(),
                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getOutcome().toString()
        );
        assertEquals(
                data.errorCode(),
                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getErrorCode().toString()
        );
    }

}
