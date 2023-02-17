package it.pagopa.transactions.commands.handlers;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.SendMessageResult;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.EuroUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class TransactionSendClosureHandlerTest {
    private final TransactionsEventStoreRepository<TransactionClosureData> transactionEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsEventStoreRepository<Void> transactionClosureErrorEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final NodeForPspClient nodeForPspClient = Mockito.mock(NodeForPspClient.class);

    private final QueueAsyncClient transactionClosureSentEventQueueClient = Mockito.mock(QueueAsyncClient.class);

    private static final int PAYMENT_TOKEN_VALIDITY = 120;
    private static final int SOFT_TIMEOUT_OFFSET = 10;
    private static final int RETRY_TIMEOUT_INTERVAL = 5;

    private final TransactionSendClosureHandler transactionSendClosureHandler = new TransactionSendClosureHandler(
            transactionEventStoreRepository,
            transactionClosureErrorEventStoreRepository,
            eventStoreRepository,
            nodeForPspClient,
            transactionClosureSentEventQueueClient,
            PAYMENT_TOKEN_VALIDITY,
            SOFT_TIMEOUT_OFFSET,
            RETRY_TIMEOUT_INTERVAL
    );

    private final TransactionId transactionId = new TransactionId(UUID.fromString(TransactionTestUtils.TRANSACTION_ID));

    @Test
    void shouldRejectTransactionInWrongState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");

        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null
                )
        );

        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                PaymentNotices.stream().map(
                        PaymentNotice -> new PaymentNotice(
                                new PaymentToken(PaymentNotice.getPaymentToken()),
                                new RptId(PaymentNotice.getRptId()),
                                new TransactionAmount(PaymentNotice.getAmount()),
                                new TransactionDescription(PaymentNotice.getDescription()),
                                new PaymentContextCode(PaymentNotice.getPaymentContextCode())
                        )
                )
                        .toList(),
                email,
                faultCode,
                faultCodeString,
                Transaction.ClientId.CHECKOUT
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        ClosureSendData closureSendData = new ClosureSendData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                closureSendData
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email.value(),
                        transaction.getTransactionActivatedData().getPaymentNotices(),
                        faultCode,
                        faultCodeString,
                        it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
        );

        TransactionClosedEvent closedEvent = TransactionTestUtils
                .transactionClosedEvent(ClosePaymentResponseDto.OutcomeEnum.OK);

        Flux events = Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closedEvent
        );

        Mockito.when(eventStoreRepository.findByTransactionId(any())).thenReturn(events);

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
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email.value(),
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosedEvent event = TransactionTestUtils
                .transactionClosedEvent(ClosePaymentResponseDto.OutcomeEnum.OK);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .expectNext(Either.right(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
    }

    @Test
    void shoulGenerateClosureFailedEventOnAuthorizationKO() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email.value(),
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils.transactionClosureFailedEvent();

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .expectNext(Either.right(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
    }

    @Test
    void shoulGenerateClosedEventOnAuthorizationOKAndOkNodoClosePayment() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email.value(),
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosedEvent event = TransactionTestUtils
                .transactionClosedEvent(ClosePaymentResponseDto.OutcomeEnum.OK);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.OK);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .expectNext(Either.right(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
    }

    @Test
    void shouldEnqueueErrorEventOnNodoFailure() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email.value(),
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils.transactionClosureFailedEvent();

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                                        .sum()
                                        + authorizationRequestData.getFee())
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode()
                        )
                );

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value().toString()
        );

        RuntimeException closePaymentError = new RuntimeException("Network error");

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .expectNext(Either.left(errorEvent))
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    @Test
    void shouldEnqueueErrorEventOnRedisFailure() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email.value(),
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getAmount() + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value().toString()
        );

        RuntimeException redisError = new RuntimeException("Network error");

        /* preconditions */
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        // first call to redis is ko, second one is ok
        Mockito.when(transactionEventStoreRepository.save(any()))
                .thenReturn(Mono.error(redisError));
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any()))
                .thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .expectNext(Either.left(errorEvent))
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));

        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    private static Mono<Response<SendMessageResult>> queueSuccessfulResponse() {
        return Mono.just(new Response<>() {
            @Override
            public int getStatusCode() {
                return 200;
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }

            @Override
            public HttpRequest getRequest() {
                return null;
            }

            @Override
            public SendMessageResult getValue() {
                return new SendMessageResult();
            }
        });
    }
}
