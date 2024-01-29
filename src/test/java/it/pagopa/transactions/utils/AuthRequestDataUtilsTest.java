package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthRequestDataUtilsTest {

    private final UUIDUtils uuidUtils = Mockito.mock(UUIDUtils.class);
    private final AuthRequestDataUtils authRequestDataUtils = new AuthRequestDataUtils(uuidUtils);

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

        assertEquals(TRANSACTION_ID_ENCODED, data.rrn());
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

        assertEquals(TRANSACTION_ID_ENCODED, data.rrn());
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

    private static Stream<Arguments> npgOutcomeTestArguments() {
        return Stream.of(
                // npg operation result - expected outcome mappings
                Arguments.arguments(OutcomeNpgGatewayDto.OperationResultEnum.AUTHORIZED, "KO"),
                Arguments.arguments(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED, "OK"),
                Arguments.arguments(OutcomeNpgGatewayDto.OperationResultEnum.DECLINED, "KO"),
                Arguments.arguments(OutcomeNpgGatewayDto.OperationResultEnum.DENIED_BY_RISK, "KO"),
                Arguments.arguments(OutcomeNpgGatewayDto.OperationResultEnum.THREEDS_VALIDATED, "KO"),
                Arguments.arguments(OutcomeNpgGatewayDto.OperationResultEnum.THREEDS_FAILED, "KO"),
                Arguments.arguments(OutcomeNpgGatewayDto.OperationResultEnum.PENDING, "KO"),
                Arguments.arguments(OutcomeNpgGatewayDto.OperationResultEnum.CANCELED, "KO"),
                Arguments.arguments(OutcomeNpgGatewayDto.OperationResultEnum.VOIDED, "KO"),
                Arguments.arguments(OutcomeNpgGatewayDto.OperationResultEnum.REFUNDED, "KO"),
                Arguments.arguments(OutcomeNpgGatewayDto.OperationResultEnum.FAILED, "KO")
        );
    }

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
        String rrn = "rrn";
        OutcomeNpgGatewayDto outcomeNpgGatewayDto = new OutcomeNpgGatewayDto()
                .paymentGatewayType("NPG")
                .orderId(orderId)
                .operationId(operationId)
                .paymentEndToEndId(paymentEndToEndId)
                .operationResult(operationResultEnum)
                .authorizationCode(authorizationCode)
                .rrn(rrn);
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(outcomeNpgGatewayDto);
        AuthRequestDataUtils.AuthRequestData authRequestData = authRequestDataUtils
                .from(updateAuthorizationRequest, transactionId);
        assertEquals(authorizationCode, authRequestData.authorizationCode());
        assertEquals(expectedOutcome, authRequestData.outcome());
        assertEquals(rrn, authRequestData.rrn());
        assertNull(authRequestData.errorCode());
    }

    @ParameterizedTest
    @EnumSource(AuthorizationOutcomeDto.class)
    void shouldExtractRedirectInformation(AuthorizationOutcomeDto authorizationOutcomeDto) {
        // pre-condition
        String pspTransactionId = TransactionTestUtils.REDIRECT_PSP_TRANSACTION_ID;
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
