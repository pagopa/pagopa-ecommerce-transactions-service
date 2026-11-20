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

    private final AuthRequestDataUtils authRequestDataUtils = new AuthRequestDataUtils();

    @ParameterizedTest
    @EnumSource(it.pagopa.generated.transactions.v2.server.model.OutcomeNpgGatewayDto.OperationResultEnum.class)
    void shouldExtractNpgInformation(
                                     it.pagopa.generated.transactions.v2.server.model.OutcomeNpgGatewayDto.OperationResultEnum operationResultEnum
    ) {
        String expectedOutcome = operationResultEnum == it.pagopa.generated.transactions.v2.server.model.OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED
                ? "OK"
                : "KO";
        String orderId = "orderId";
        String operationId = "operationId";
        String paymentEndToEndId = "paymentEndToEndId";
        String authorizationCode = "authorizationCode";
        String errorCode = "errorCode";
        String rrn = "rrn";
        it.pagopa.generated.transactions.v2.server.model.OutcomeNpgGatewayDto outcomeNpgGatewayDto = new it.pagopa.generated.transactions.v2.server.model.OutcomeNpgGatewayDto()
                .paymentGatewayType("NPG")
                .orderId(orderId)
                .operationId(operationId)
                .paymentEndToEndId(paymentEndToEndId)
                .operationResult(operationResultEnum)
                .authorizationCode(authorizationCode)
                .errorCode(errorCode)
                .rrn(rrn);
        it.pagopa.generated.transactions.v2.server.model.UpdateAuthorizationRequestDto updateAuthorizationRequest = new it.pagopa.generated.transactions.v2.server.model.UpdateAuthorizationRequestDto()
                .outcomeGateway(outcomeNpgGatewayDto);
        AuthRequestDataUtils.AuthRequestData authRequestData = authRequestDataUtils
                .from(updateAuthorizationRequest);
        assertEquals(authorizationCode, authRequestData.authorizationCode());
        assertEquals(expectedOutcome, authRequestData.outcome());
        assertEquals(rrn, authRequestData.rrn());
        assertEquals(errorCode, authRequestData.errorCode());
    }

    @ParameterizedTest
    @EnumSource(it.pagopa.generated.transactions.v2.server.model.AuthorizationOutcomeDto.class)
    void shouldExtractRedirectInformation(
                                          it.pagopa.generated.transactions.v2.server.model.AuthorizationOutcomeDto authorizationOutcomeDto
    ) {
        // pre-condition
        String pspTransactionId = TransactionTestUtils.AUTHORIZATION_REQUEST_ID;
        String pspId = TransactionTestUtils.PSP_ID;
        String expectedOutcome = authorizationOutcomeDto == it.pagopa.generated.transactions.v2.server.model.AuthorizationOutcomeDto.OK
                ? "OK"
                : "KO";
        String errorCode = "errorCode";
        String authorizationCode = "authorizationCode";
        it.pagopa.generated.transactions.v2.server.model.OutcomeRedirectGatewayDto outcomeRedirectGatewayDto = new it.pagopa.generated.transactions.v2.server.model.OutcomeRedirectGatewayDto()
                .paymentGatewayType("REDIRECT")
                .outcome(authorizationOutcomeDto)
                .pspTransactionId(pspTransactionId)
                .errorCode(errorCode)
                .authorizationCode(authorizationCode)
                .pspId(pspId);
        it.pagopa.generated.transactions.v2.server.model.UpdateAuthorizationRequestDto updateAuthorizationRequest = new it.pagopa.generated.transactions.v2.server.model.UpdateAuthorizationRequestDto()
                .outcomeGateway(outcomeRedirectGatewayDto);
        // test
        AuthRequestDataUtils.AuthRequestData authRequestData = authRequestDataUtils
                .from(updateAuthorizationRequest);
        // assertions
        assertEquals(expectedOutcome, authRequestData.outcome());
        assertEquals(errorCode, authRequestData.errorCode());
        assertEquals(authorizationCode, authRequestData.authorizationCode());
        assertNull(authRequestData.rrn());
    }

}
