package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NotificationsServiceClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.domain.TransactionActivated;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionEventCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionSendClosureHandlerTest {

    @InjectMocks
    private TransactionSendClosureHandler transactionSendClosureHandler;

    @Mock
    private TransactionsEventStoreRepository<TransactionClosureSendData> transactionEventStoreRepository;

    @Mock
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionAuthorizationEventStoreRepository;

    @Mock
    private TransactionsEventStoreRepository<Object> eventStoreRepository;

    @Mock
    NodeForPspClient nodeForPspClient;

    @Mock
    NotificationsServiceClient notificationsServiceClient;

    private TransactionId transactionId = new TransactionId(UUID.randomUUID());

    @Test
    void shouldRejectTransactionInWrongState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");

        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");

        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                email,
                faultCode,
                faultCodeString,
                TransactionStatusDto.AUTHORIZATION_REQUESTED
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        ClosureSendData closureSendData = new ClosureSendData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(transaction.getRptId(), closureSendData);

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionActivatedData(
                        description.value(),
                        amount.value(),
                        email.value(),
                        faultCode,
                        faultCodeString,
                        "paymentToken"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED
                )
        );

        TransactionClosureSentEvent closureSentEvent = new TransactionClosureSentEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionClosureSendData(
                        ClosePaymentResponseDto.OutcomeEnum.OK,
                        TransactionStatusDto.CLOSED
                )
        );

        Flux events = Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationStatusUpdatedEvent,
                closureSentEvent,
                closureSentEvent
        );

        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldSetTransactionStatusToClosureFailedOnNodoKO() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionActivatedData(
                        description.value(),
                        amount.value(),
                        email.value(),
                        faultCode,
                        faultCodeString,
                        "paymentToken"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent));

        it.pagopa.transactions.domain.Transaction transaction = events.reduce(new EmptyTransaction(), Transaction::applyEvent).block();

        TransactionClosureSendData transactionClosureSendData = new TransactionClosureSendData(ClosePaymentResponseDto.OutcomeEnum.KO, TransactionStatusDto.CLOSURE_FAILED);
        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(new RptId(transactionActivatedEvent.getRptId()), closureSendData);

        TransactionClosureSentEvent event = new TransactionClosureSentEvent(
                transactionId.toString(),
                transactionActivatedEvent.getRptId(),
                transactionActivatedEvent.getData().getPaymentToken(),
                transactionClosureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = new TransactionAuthorizationRequestData(
                amount.value(),
                1,
                "paymentInstrumentId",
                "pspId",
                "",
                "brokerName",
                "pspChannelCode"
        );
        TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent =
                new TransactionAuthorizationRequestedEvent(
                        transactionId.value().toString(),
                        rptId.value(),
                        paymentToken.value(),
                        authorizationRequestData
                );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(List.of(((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData().getPaymentToken()))
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(new BigDecimal(((BaseTransactionWithPaymentToken) transaction).getAmount().value() + authorizationRequestData.getFee()))
                .fee(new BigDecimal(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway", updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code", updateAuthorizationRequest.getAuthorizationCode()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        Mockito.when(transactionAuthorizationEventStoreRepository.findByTransactionIdAndEventCode(transactionId.value().toString(), TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT))
                .thenReturn(Mono.just(transactionAuthorizationRequestedEvent));

        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        Mockito.when(notificationsServiceClient.sendSuccessEmail(any())).thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));

        /* test */
        StepVerifier.create(
                transactionSendClosureHandler.handle(closureSendCommand)
                )
                .expectNextMatches(closureSentEvent -> closureSentEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(argThat(closureSendDataEvent -> closureSendDataEvent.getData().getNewTransactionStatus().equals(TransactionStatusDto.CLOSURE_FAILED)));
    }
}
