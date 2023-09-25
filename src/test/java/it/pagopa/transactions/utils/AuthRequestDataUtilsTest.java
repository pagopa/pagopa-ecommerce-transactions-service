package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AuthRequestDataUtilsTest {

    @InjectMocks
    private AuthRequestDataUtils authRequestDataUtils;
    @Mock
    private UUIDUtils uuidUtils;

    private static final String TRANSACTION_ID_ENCODED = "transactionIdEncoded";

    private static Set<OutcomeNpgGatewayDto.OperationResultEnum> testedStatuses = new HashSet<>();

    @AfterAll
    public static void afterAllTests() {
        Set<OutcomeNpgGatewayDto.OperationResultEnum> untestedOperationResults = Arrays
                .stream(OutcomeNpgGatewayDto.OperationResultEnum.values())
                .filter(Predicate.not(testedStatuses::contains))
                .collect(Collectors.toSet());
        assertTrue(
                untestedOperationResults.isEmpty(),
                "Untested outcome detected! %s".formatted(untestedOperationResults)
        );
    }

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

    @ParameterizedTest
    @MethodSource("npgOutcomeTestArguments")
    void shouldExtractNpgInformation(
                                     OutcomeNpgGatewayDto.OperationResultEnum operationResultEnum,
                                     String expectedOutcome
    ) {
        testedStatuses.add(operationResultEnum);
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        String orderId = "orderId";
        String operationId = "operationId";
        String paymentEndToEndId = "paymentEndToEndId";
        String authorizationCode = "authorizationCode";
        OutcomeNpgGatewayDto outcomeNpgGatewayDto = new OutcomeNpgGatewayDto()
                .paymentGatewayType("NPG")
                .orderId(orderId)
                .operationId(operationId)
                .paymentEndToEndId(paymentEndToEndId)
                .operationResult(operationResultEnum)
                .authorizationCode(authorizationCode);
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(outcomeNpgGatewayDto);
        AuthRequestDataUtils.AuthRequestData authRequestData = authRequestDataUtils
                .from(updateAuthorizationRequest, transactionId);
        assertEquals(authorizationCode, authRequestData.authorizationCode());
        assertEquals(expectedOutcome, authRequestData.outcome());
        assertEquals(paymentEndToEndId, authRequestData.rrn());
        assertNull(authRequestData.errorCode());
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
}
