package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthRequestDataUtilsTest {

    private final UUIDUtils uuidUtils = Mockito.mock(UUIDUtils.class);
    private final AuthRequestDataUtils authRequestDataUtils = new AuthRequestDataUtils(uuidUtils);

    private static final String TRANSACTION_ID_ENCODED = "transactionIdEncoded";

    @ParameterizedTest
    @EnumSource(OutcomeNpgGatewayDto.OperationResultEnum.class)
    void shouldExtractNpgInformation(
                                     OutcomeNpgGatewayDto.OperationResultEnum operationResultEnum
    ) {
        String expectedOutcome = operationResultEnum == OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED ? "OK" : "KO";
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        String orderId = "orderId";
        String operationId = "operationId";
        String paymentEndToEndId = "paymentEndToEndId";
        String authorizationCode = "authorizationCode";
        String errorCode = "errorCode";
        String rrn = "rrn";
        OutcomeNpgGatewayDto outcomeNpgGatewayDto = new OutcomeNpgGatewayDto()
                .paymentGatewayType("NPG")
                .orderId(orderId)
                .operationId(operationId)
                .paymentEndToEndId(paymentEndToEndId)
                .operationResult(operationResultEnum)
                .authorizationCode(authorizationCode)
                .errorCode(errorCode)
                .rrn(rrn);
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(outcomeNpgGatewayDto);
        AuthRequestDataUtils.AuthRequestData authRequestData = authRequestDataUtils
                .from(updateAuthorizationRequest, transactionId);
        assertEquals(authorizationCode, authRequestData.authorizationCode());
        assertEquals(expectedOutcome, authRequestData.outcome());
        assertEquals(rrn, authRequestData.rrn());
        assertEquals(errorCode, authRequestData.errorCode());
    }

    @ParameterizedTest
    @EnumSource(AuthorizationOutcomeDto.class)
    void shouldExtractRedirectInformation(AuthorizationOutcomeDto authorizationOutcomeDto) {
        // pre-condition
        String pspTransactionId = TransactionTestUtils.AUTHORIZATION_REQUEST_ID;
        String pspId = TransactionTestUtils.PSP_ID;
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);
        String expectedOutcome = authorizationOutcomeDto == AuthorizationOutcomeDto.OK ? "OK" : "KO";
        String errorCode = "errorCode";
        String authorizationCode = "authorizationCode";
        OutcomeRedirectGatewayDto outcomeRedirectGatewayDto = new OutcomeRedirectGatewayDto()
                .paymentGatewayType("REDIRECT")
                .outcome(authorizationOutcomeDto)
                .pspTransactionId(pspTransactionId)
                .errorCode(errorCode)
                .authorizationCode(authorizationCode)
                .pspId(pspId);
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(outcomeRedirectGatewayDto);
        // test
        AuthRequestDataUtils.AuthRequestData authRequestData = authRequestDataUtils
                .from(updateAuthorizationRequest, transactionId);
        // assertions
        assertEquals(expectedOutcome, authRequestData.outcome());
        assertEquals(errorCode, authRequestData.errorCode());
        assertEquals(authorizationCode, authRequestData.authorizationCode());
        assertNull(authRequestData.rrn());
    }

}
