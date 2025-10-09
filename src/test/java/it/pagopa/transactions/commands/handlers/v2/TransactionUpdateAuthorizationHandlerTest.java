package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.*;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.WalletInfo;
import it.pagopa.ecommerce.commons.domain.v2.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.v2.TransactionEventCode;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.generated.wallet.v1.dto.WalletNotificationRequestCardDetailsDto;
import it.pagopa.generated.wallet.v1.dto.WalletNotificationRequestDto;
import it.pagopa.transactions.client.WalletClient;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.configurations.WalletConfig;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionUpdateAuthorizationHandlerTest {

    private final TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsEventStoreRepository eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);
    private TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);

    private final UUIDUtils mockUuidUtils = Mockito.mock(UUIDUtils.class);

    private final TransactionsUtils transactionsUtils = new TransactionsUtils(
            eventStoreRepository,
            "warmUpNoticeCodePrefix"
    );

    private final WalletClient walletClient = Mockito.mock(WalletClient.class);

    private final WalletConfig walletConfig = new WalletConfig(
            "http://localhost",
            1000,
            1000,
            "api-key",
            new WalletConfig.NotificationConf(
                    3,
                    1
            )
    );

    private final it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler updateAuthorizationHandler = new TransactionUpdateAuthorizationHandler(
            transactionEventStoreRepository,
            new AuthRequestDataUtils(mockUuidUtils),
            transactionsUtils,
            walletClient,
            walletConfig
    );

    @BeforeEach
    public void initializeTests() {
        updateAuthorizationHandler.subscribeToAuthorizationCommandSink();
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
                updateAuthorizationStatusData,
                List.of(activatedEvent, authorizationRequestedEvent)
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
        Mockito.verify(walletClient, Mockito.timeout(2000).times(0)).notifyWallet(any(), any(), any());
    }

    @Test
    void shouldThrowExceptionForUnhandledOutcomeGatewayDto() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent(
                        TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
                        TransactionTestUtils.redirectTransactionGatewayAuthorizationRequestedData()
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
                updateAuthorizationStatusData,
                List.of(activatedEvent, authorizationRequestedEvent)
        );

        /* preconditions */

        AuthRequestDataUtils authRequestDataUtilsMock = Mockito.mock(AuthRequestDataUtils.class);
        Mockito.when(authRequestDataUtilsMock.from(any(), any()))
                .thenReturn(new AuthRequestDataUtils.AuthRequestData("", "", "", ""));
        TransactionUpdateAuthorizationHandler updateAuthHandler = new TransactionUpdateAuthorizationHandler(
                transactionEventStoreRepository,
                authRequestDataUtilsMock,
                transactionsUtils,
                walletClient,
                walletConfig
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
                updateAuthorizationStatusData,
                List.of(activatedEvent, authorizationRequestedEvent)
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
        Hooks.onOperatorDebug();
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

        Mockito.verify(walletClient, Mockito.timeout(2000).times(0)).notifyWallet(any(), any(), any());
    }

    @Test
    void shouldPerformPostWalletNotificationCallForPaymentWithContextualOnboarding() {
        WalletInfo walletInfo = new WalletInfo(TransactionTestUtils.NPG_WALLET_ID, null);
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        // authorization performed with a card wallet
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent(
                        TransactionTestUtils.npgTransactionGatewayAuthorizationRequestedData(walletInfo)
                );
        authorizationRequestedEvent.getData().setPaymentTypeCode("CP");
        NpgTransactionGatewayAuthorizationData npgTransactionGatewayAuthorizationData = (NpgTransactionGatewayAuthorizationData) TransactionTestUtils
                .npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED);
        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        npgTransactionGatewayAuthorizationData
                );
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);
        String cardId4 = "cardId4";
        OutcomeNpgGatewayDto outcomeNpgGatewayDto = new OutcomeNpgGatewayDto()
                .paymentGatewayType("NPG")
                .operationResult(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)
                .authorizationCode("1234")
                .paymentEndToEndId("paymentEndToEndId")
                .cardId4(cardId4);
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(outcomeNpgGatewayDto)
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
                updateAuthorizationStatusData,
                List.of(activatedEvent, authorizationRequestedEvent)
        );
        WalletNotificationRequestDto expectedWalletNotificationRequest = new WalletNotificationRequestDto()
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .operationId(outcomeNpgGatewayDto.getOperationId())
                .operationResult(
                        WalletNotificationRequestDto.OperationResultEnum
                                .valueOf(outcomeNpgGatewayDto.getOperationResult().toString())
                )
                .errorCode(outcomeNpgGatewayDto.getErrorCode())
                .details(
                        new WalletNotificationRequestCardDetailsDto()
                                .paymentInstrumentGatewayId(cardId4)
                                .type("CARD")
                );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(mockUuidUtils.uuidToBase64(transactionId.uuid()))
                .thenReturn(transactionId.uuid().toString());
        Mockito.when(walletClient.notifyWallet(any(), any(), any())).thenReturn(Mono.empty());
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
        Mockito.verify(walletClient, Mockito.timeout(2000).times(1)).notifyWallet(
                TransactionTestUtils.NPG_WALLET_ID,
                outcomeNpgGatewayDto.getOrderId(),
                expectedWalletNotificationRequest
        );
    }

    @Test
    void shouldPerformPostWalletNotificationCallForPaymentWithContextualOnboardingWithRetryInCaseOfErrors() {
        WalletInfo walletInfo = new WalletInfo(TransactionTestUtils.NPG_WALLET_ID, null);
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        // authorization performed with a card wallet
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent(
                        TransactionTestUtils.npgTransactionGatewayAuthorizationRequestedData(walletInfo)
                );
        authorizationRequestedEvent.getData().setPaymentTypeCode("CP");
        NpgTransactionGatewayAuthorizationData npgTransactionGatewayAuthorizationData = (NpgTransactionGatewayAuthorizationData) TransactionTestUtils
                .npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED);
        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        npgTransactionGatewayAuthorizationData
                );
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);
        String cardId4 = "cardId4";
        OutcomeNpgGatewayDto outcomeNpgGatewayDto = new OutcomeNpgGatewayDto()
                .paymentGatewayType("NPG")
                .operationResult(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)
                .authorizationCode("1234")
                .paymentEndToEndId("paymentEndToEndId")
                .cardId4(cardId4);
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(outcomeNpgGatewayDto)
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
                updateAuthorizationStatusData,
                List.of(activatedEvent, authorizationRequestedEvent)
        );
        WalletNotificationRequestDto expectedWalletNotificationRequest = new WalletNotificationRequestDto()
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .operationId(outcomeNpgGatewayDto.getOperationId())
                .operationResult(
                        WalletNotificationRequestDto.OperationResultEnum
                                .valueOf(outcomeNpgGatewayDto.getOperationResult().toString())
                )
                .errorCode(outcomeNpgGatewayDto.getErrorCode())
                .details(
                        new WalletNotificationRequestCardDetailsDto()
                                .paymentInstrumentGatewayId(cardId4)
                                .type("CARD")
                );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(mockUuidUtils.uuidToBase64(transactionId.uuid()))
                .thenReturn(transactionId.uuid().toString());
        Mockito.when(walletClient.notifyWallet(any(), any(), any())).thenReturn(
                Mono.error(new RuntimeException("Exception communicating with wallet first attempt")),
                Mono.error(new RuntimeException("Exception communicating with wallet second attempt")),
                Mono.empty()
        );
        Hooks.onOperatorDebug();
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
        Mockito.verify(walletClient, Mockito.timeout(5000).times(3)).notifyWallet(
                TransactionTestUtils.NPG_WALLET_ID,
                outcomeNpgGatewayDto.getOrderId(),
                expectedWalletNotificationRequest
        );
    }

    @Test
    void shouldHandleWalletNotificationErrorWithoutBlockingPaymentFlow() {
        WalletInfo walletInfo = new WalletInfo(TransactionTestUtils.NPG_WALLET_ID, null);
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        // authorization performed with a card wallet
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent(
                        TransactionTestUtils.npgTransactionGatewayAuthorizationRequestedData(walletInfo)
                );
        authorizationRequestedEvent.getData().setPaymentTypeCode("CP");
        NpgTransactionGatewayAuthorizationData npgTransactionGatewayAuthorizationData = (NpgTransactionGatewayAuthorizationData) TransactionTestUtils
                .npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED);
        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        npgTransactionGatewayAuthorizationData
                );
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);
        String cardId4 = "cardId4";
        OutcomeNpgGatewayDto outcomeNpgGatewayDto = new OutcomeNpgGatewayDto()
                .paymentGatewayType("NPG")
                .operationResult(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)
                .authorizationCode("1234")
                .paymentEndToEndId("paymentEndToEndId")
                .cardId4(cardId4);
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(outcomeNpgGatewayDto)
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
                updateAuthorizationStatusData,
                List.of(activatedEvent, authorizationRequestedEvent)
        );
        WalletNotificationRequestDto expectedWalletNotificationRequest = new WalletNotificationRequestDto()
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .operationId(outcomeNpgGatewayDto.getOperationId())
                .operationResult(
                        WalletNotificationRequestDto.OperationResultEnum
                                .valueOf(outcomeNpgGatewayDto.getOperationResult().toString())
                )
                .errorCode(outcomeNpgGatewayDto.getErrorCode())
                .details(
                        new WalletNotificationRequestCardDetailsDto()
                                .paymentInstrumentGatewayId(cardId4)
                                .type("CARD")
                );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(mockUuidUtils.uuidToBase64(transactionId.uuid()))
                .thenReturn(transactionId.uuid().toString());
        Mockito.when(walletClient.notifyWallet(any(), any(), any())).thenReturn(
                Mono.error(new RuntimeException("Exception communicating with wallet"))
        );
        Hooks.onOperatorDebug();
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
        Mockito.verify(walletClient, Mockito.timeout(5000).times(3)).notifyWallet(
                TransactionTestUtils.NPG_WALLET_ID,
                outcomeNpgGatewayDto.getOrderId(),
                expectedWalletNotificationRequest
        );
    }

}
