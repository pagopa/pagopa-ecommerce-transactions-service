package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.*;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.PgsTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v2.TransactionEventCode;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionUpdateAuthorizationHandlerTest {

    private TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private TransactionsEventStoreRepository eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);
    private TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);

    private final UUIDUtils mockUuidUtils = Mockito.mock(UUIDUtils.class);

    private final TransactionsUtils transactionsUtils = new TransactionsUtils(
            eventStoreRepository,
            "warmUpNoticeCodePrefix"
    );

    private final Map<String, URI> npgPaymentCircuitLogoMap = Map.of(
            "VISA",
            URI.create("logo/visa"),
            "UNKNOWN",
            URI.create("logo/unknown")
    );

    private it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler updateAuthorizationHandler = new TransactionUpdateAuthorizationHandler(
            transactionEventStoreRepository,
            new AuthRequestDataUtils(mockUuidUtils),
            transactionsUtils,
            npgPaymentCircuitLogoMap
    );

    @Test
    void shouldSaveSuccessfulUpdateXpay() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        new PgsTransactionGatewayAuthorizationData(null, AuthorizationResultDto.OK)
                );
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequest,
                ZonedDateTime.now(),
                Optional.of(transaction)
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                updateAuthorizationStatusData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(mockUuidUtils.uuidToBase64(transactionId.uuid()))
                .thenReturn(transactionId.uuid().toString());
        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1))
                .save(
                        argThat(
                                eventArg -> TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                                        .equals(eventArg.getEventCode())
                                        && ((PgsTransactionGatewayAuthorizationData) eventArg.getData()
                                                .getTransactionGatewayAuthorizationData()).getAuthorizationResultDto()
                                                        .equals(AuthorizationResultDto.OK)
                        )
                );
    }

    @Test
    void shouldSaveSuccessfulUpdateVpos() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        new PgsTransactionGatewayAuthorizationData(null, AuthorizationResultDto.OK)
                );
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequest,
                ZonedDateTime.now(),
                Optional.of(transaction)
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                updateAuthorizationStatusData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(mockUuidUtils.uuidToBase64(transactionId.uuid()))
                .thenReturn(transactionId.uuid().toString());
        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1))
                .save(
                        argThat(
                                eventArg -> TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                                        .equals(eventArg.getEventCode())
                                        && ((PgsTransactionGatewayAuthorizationData) eventArg.getData()
                                                .getTransactionGatewayAuthorizationData()).getAuthorizationResultDto()
                                                        .equals(AuthorizationResultDto.OK)
                        )
                );
    }

    @Test
    void shouldSaveSuccessfulUpdateNpg() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();
        NpgTransactionGatewayAuthorizationData npgTransactionGatewayAuthorizationData = (NpgTransactionGatewayAuthorizationData) TransactionTestUtils
                .npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED);
        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        npgTransactionGatewayAuthorizationData
                );
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeNpgGatewayDto()
                                .paymentGatewayType("NPG")
                                .operationResult(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)
                                .authorizationCode("1234")
                                .paymentEndToEndId("paymentEndToEndId")
                )
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequest,
                ZonedDateTime.now(),
                Optional.of(transaction)
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                updateAuthorizationStatusData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(mockUuidUtils.uuidToBase64(transactionId.uuid()))
                .thenReturn(transactionId.uuid().toString());
        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1))
                .save(
                        argThat(
                                eventArg -> {
                                    NpgTransactionGatewayAuthorizationData npgData = (NpgTransactionGatewayAuthorizationData) eventArg
                                            .getData().getTransactionGatewayAuthorizationData();
                                    return TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                                            .equals(eventArg.getEventCode())
                                            && npgData.getOperationResult()
                                                    .equals(
                                                            OperationResultDto.valueOf(
                                                                    ((OutcomeNpgGatewayDto) updateAuthorizationRequest
                                                                            .getOutcomeGateway())
                                                                                    .getOperationResult().getValue()
                                                            )
                                                    );
                                }
                        )
                );
    }

    @Test
    void shouldRejectTransactionInInvalidState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                List.of(new PaymentTransferInfo(rptId.getFiscalCode(), false, amount.value(), null)),
                                false,
                                new CompanyName(null)
                        )
                ),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new EmptyTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequest,
                ZonedDateTime.now(),
                Optional.of(transaction)
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                updateAuthorizationStatusData
        );

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldSetTransactionStatusToAuthorizationFailedOnGatewayKO() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        new PgsTransactionGatewayAuthorizationData(null, AuthorizationResultDto.OK)
                );
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.KO)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequest,
                ZonedDateTime.now(),

                Optional.of(transaction)
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                updateAuthorizationStatusData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                                && ((PgsTransactionGatewayAuthorizationData) eventArg.getData()
                                        .getTransactionGatewayAuthorizationData()).getAuthorizationResultDto()
                                                .equals(AuthorizationResultDto.KO)
                )
        );
    }

    @Test
    void shouldThrowExceptionForUnhandledOutcomeGatewayDto() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent(
                        TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
                        TransactionTestUtils.redirectTransactionGatewayAuthorizationRequestedData()
                );
        RedirectTransactionGatewayAuthorizationData.Outcome authOutcome = RedirectTransactionGatewayAuthorizationData.Outcome.OK;
        String errorCode = "errorCode";
        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        TransactionTestUtils.redirectTransactionGatewayAuthorizationData(
                                authOutcome,
                                errorCode
                        )
                );
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        Mockito.mock(UpdateAuthorizationRequestOutcomeGatewayDto.class)
                )
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequest,
                ZonedDateTime.now(),
                Optional.of(transaction)
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                updateAuthorizationStatusData
        );

        /* preconditions */

        AuthRequestDataUtils authRequestDataUtilsMock = Mockito.mock(AuthRequestDataUtils.class);
        Mockito.when(authRequestDataUtilsMock.from(any(), any()))
                .thenReturn(new AuthRequestDataUtils.AuthRequestData("", "", "", ""));
        TransactionUpdateAuthorizationHandler updateAuthHandler = new TransactionUpdateAuthorizationHandler(
                transactionEventStoreRepository,
                authRequestDataUtilsMock,
                transactionsUtils,
                npgPaymentCircuitLogoMap
        );
        /* test */
        assertThrows(InvalidRequestException.class, () -> updateAuthHandler.handle(requestAuthorizationCommand));

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0))
                .save(any());
    }

    @Test
    void shouldHandleTransactionUpdateCommandForRedirectPaymentSuccessfully() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent(
                        TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
                        TransactionTestUtils.redirectTransactionGatewayAuthorizationRequestedData()
                );
        RedirectTransactionGatewayAuthorizationData.Outcome authOutcome = RedirectTransactionGatewayAuthorizationData.Outcome.OK;
        String errorCode = "errorCode";
        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        TransactionTestUtils.redirectTransactionGatewayAuthorizationData(
                                authOutcome,
                                errorCode
                        )
                );
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeRedirectGatewayDto()
                                .outcome(AuthorizationOutcomeDto.OK)
                                .paymentGatewayType("REDIRECT")
                                .errorCode(errorCode)
                                .authorizationCode(TransactionTestUtils.AUTHORIZATION_CODE)
                                .pspTransactionId(TransactionTestUtils.AUTHORIZATION_REQUEST_ID)
                                .pspId(TransactionTestUtils.PSP_ID)
                )
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequest,
                ZonedDateTime.now(),
                Optional.of(transaction)
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                updateAuthorizationStatusData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(mockUuidUtils.uuidToBase64(transactionId.uuid()))
                .thenReturn(transactionId.uuid().toString());
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        Flux.fromIterable(
                                List.of(
                                        activatedEvent,
                                        authorizationRequestedEvent
                                )
                        )
                );
        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1))
                .save(
                        argThat(
                                eventArg -> {

                                    assertEquals(
                                            TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString(),
                                            eventArg.getEventCode()
                                    );
                                    RedirectTransactionGatewayAuthorizationData redirectTransactionGatewayAuthorizationData = (RedirectTransactionGatewayAuthorizationData) eventArg
                                            .getData().getTransactionGatewayAuthorizationData();
                                    assertEquals(errorCode, redirectTransactionGatewayAuthorizationData.getErrorCode());
                                    assertEquals(authOutcome, redirectTransactionGatewayAuthorizationData.getOutcome());
                                    assertEquals(
                                            TransactionTestUtils.AUTHORIZATION_CODE,
                                            eventArg.getData().getAuthorizationCode()
                                    );
                                    return true;

                                }
                        )
                );
    }

}
