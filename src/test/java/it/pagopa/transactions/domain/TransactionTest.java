package it.pagopa.transactions.domain;

import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZonedDateTime;
import java.util.UUID;

class TransactionTest {
    @Test
    void shouldConstructTransaction() {
        TransactionId transactionId = new TransactionId(UUID.fromString("833d303a-f857-11ec-b939-0242ac120002"));
        PaymentToken paymentToken = new PaymentToken("");
        RptId rptId = new RptId("77777777777302016723749670035");
        TransactionDescription description = new TransactionDescription("");
        TransactionAmount amount = new TransactionAmount(100);
        TransactionStatusDto status = TransactionStatusDto.ACTIVATED;

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                status
        );

        assertEquals(new PaymentToken(transaction.getTransactionActivatedData().getPaymentToken()), paymentToken);
        assertEquals(transaction.getRptId(), rptId);
        assertEquals(transaction.getDescription(), description);
        assertEquals(transaction.getAmount(), amount);
        assertEquals(transaction.getStatus(), status);
    }

    @Test
    void shouldIgnoreInvalidEventStream() {
        EmptyTransaction transaction = new EmptyTransaction();

        String trxId = UUID.randomUUID().toString();
        String rptId = "rptId";
        String paymentToken = "paymentToken";
        int amount = 100;

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionAuthorizationRequestData(
                        amount,
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        Flux<TransactionEvent<?>> events = Flux.just(authorizationRequestedEvent);

        EmptyTransaction expected = new EmptyTransaction();

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent2);

        StepVerifier.create(actual)
                .expectNext(expected)
                .verifyComplete();

    }

    @Test
    void shouldConstructTransactionFromInitEventStream() {
        EmptyTransaction transaction = new EmptyTransaction();

        String trxId = UUID.randomUUID().toString();
        String rptId = "rptId";
        String paymentToken = "paymentToken";
        String description = "description";
        int amount = 100;
        TransactionActivatedEvent event = new TransactionActivatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionActivatedData(
                        description,
                        amount,
                        "email",
                        "faultCode",
                        "faultCodeString",
                        "paymentToken"
                ));

        Flux<TransactionEvent<?>> events = Flux.just(event);

        TransactionActivated expected = new TransactionActivated(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                ZonedDateTime.parse(event.getCreationDate()),
                TransactionStatusDto.ACTIVATED
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent2);

        StepVerifier.create(actual)
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldConstructTransactionFromInitEventStreamIgnoringInvalidEvents() {
        EmptyTransaction transaction = new EmptyTransaction();

        String trxId = UUID.randomUUID().toString();
        String rptId = "rptId";
        String paymentToken = "paymentToken";
        String description = "description";
        int amount = 100;
        TransactionActivatedEvent event = new TransactionActivatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionActivatedData(
                        description,
                        amount,
                        "email",
                        "faultCode",
                        "faultCodeString",
                        "paymentToken"
                ));

        Flux<TransactionEvent<?>> events = Flux.just(event, event);

        TransactionActivated expected = new TransactionActivated(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                ZonedDateTime.parse(event.getCreationDate()),
                TransactionStatusDto.ACTIVATED
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent2);

        StepVerifier.create(actual)
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldConstructTransactionFromAuthRequestEventStream() {
        EmptyTransaction transaction = new EmptyTransaction();

        String trxId = UUID.randomUUID().toString();
        String rptId = "rptId";
        String paymentToken = "paymentToken";
        String description = "description";
        int amount = 100;

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionActivatedData(
                        description,
                        amount,
                        "email",
                        "faultCode",
                        "faultCodeString",
                        "paymentToken"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionAuthorizationRequestData(
                        amount,
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        Flux<TransactionEvent<?>> events = Flux.just(transactionActivatedEvent, authorizationRequestedEvent);

        TransactionActivated transactionInitialized = new TransactionActivated(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                ZonedDateTime.parse(transactionActivatedEvent.getCreationDate()),
                TransactionStatusDto.ACTIVATED
        );

        TransactionWithRequestedAuthorization expected = new TransactionWithRequestedAuthorization(
                transactionInitialized,
                authorizationRequestedEvent
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent2);

        StepVerifier.create(actual)
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldConstructTransactionFromAuthRequestEventStreamIgnoringInvalidEvents() {
        EmptyTransaction transaction = new EmptyTransaction();

        String trxId = UUID.randomUUID().toString();
        String rptId = "rptId";
        String paymentToken = "paymentToken";
        String description = "description";
        int amount = 100;

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionActivatedData(
                        description,
                        amount,
                        "email",
                        "faultCode",
                        "faultCodeString",
                        "paymentToken"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionAuthorizationRequestData(
                        amount,
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        Flux<TransactionEvent<?>> events = Flux.just(transactionActivatedEvent, authorizationRequestedEvent, authorizationRequestedEvent);

        TransactionActivated transactionInitialized = new TransactionActivated(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                ZonedDateTime.parse(transactionActivatedEvent.getCreationDate()),
                TransactionStatusDto.ACTIVATED
        );

        TransactionWithRequestedAuthorization expected = new TransactionWithRequestedAuthorization(
                transactionInitialized,
                authorizationRequestedEvent
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent2);

        StepVerifier.create(actual)
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldConstructTransactionFromAuthCompletedEventStream() {
        EmptyTransaction transaction = new EmptyTransaction();

        String trxId = UUID.randomUUID().toString();
        String rptId = "rptId";
        String paymentToken = "paymentToken";
        String description = "description";
        int amount = 100;

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionActivatedData(
                        description,
                        amount,
                        "email",
                        "faultCode",
                        "faultCodeString",
                        "paymentToken"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionAuthorizationRequestData(
                        amount,
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED
                )
        );

        Flux<TransactionEvent<?>> events = Flux.just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent);

        TransactionActivated transactionInitialized = new TransactionActivated(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                ZonedDateTime.parse(transactionActivatedEvent.getCreationDate()),
                TransactionStatusDto.ACTIVATED
        );

        TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = new TransactionWithRequestedAuthorization(
                transactionInitialized,
                authorizationRequestedEvent
        );

        TransactionWithCompletedAuthorization expected = new TransactionWithCompletedAuthorization(
                transactionWithRequestedAuthorization,
                authorizationStatusUpdatedEvent
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent2);

        StepVerifier.create(actual)
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldConstructTransactionFromAuthCompletedEventStreamIgnoringInvalidEvents() {
        EmptyTransaction transaction = new EmptyTransaction();

        String trxId = UUID.randomUUID().toString();
        String rptId = "rptId";
        String paymentToken = "paymentToken";
        String description = "description";
        int amount = 100;

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionActivatedData(
                        description,
                        amount,
                        "email",
                        "faultCode",
                        "faultCodeString",
                        "paymentToken"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionAuthorizationRequestData(
                        amount,
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED
                )
        );

        Flux<TransactionEvent<?>> events = Flux.just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent, authorizationStatusUpdatedEvent);

        TransactionActivated transactionInitialized = new TransactionActivated(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                ZonedDateTime.parse(transactionActivatedEvent.getCreationDate()),
                TransactionStatusDto.ACTIVATED
        );

        TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = new TransactionWithRequestedAuthorization(
                transactionInitialized,
                authorizationRequestedEvent
        );

        TransactionWithCompletedAuthorization expected = new TransactionWithCompletedAuthorization(
                transactionWithRequestedAuthorization,
                authorizationStatusUpdatedEvent
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent2);

        StepVerifier.create(actual)
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldConstructTransactionFromClosureSentEventStream() {
        EmptyTransaction transaction = new EmptyTransaction();

        String trxId = UUID.randomUUID().toString();
        String rptId = "rptId";
        String paymentToken = "paymentToken";
        String description = "description";
        int amount = 100;

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionActivatedData(
                        description,
                        amount,
                        "email",
                        "faultCode",
                        "faultCodeString",
                        "paymentToken"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionAuthorizationRequestData(
                        amount,
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED
                )
        );

        TransactionClosureSentEvent closureSentEvent = new TransactionClosureSentEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionClosureSendData(
                        ClosePaymentResponseDto.EsitoEnum.OK,
                        TransactionStatusDto.CLOSED
                )
        );

        Flux<TransactionEvent<?>> events = Flux.just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent, closureSentEvent);

        TransactionActivated transactionInitialized = new TransactionActivated(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                ZonedDateTime.parse(transactionActivatedEvent.getCreationDate()),
                TransactionStatusDto.ACTIVATED
        );

        TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = new TransactionWithRequestedAuthorization(
                transactionInitialized,
                authorizationRequestedEvent
        );

        TransactionWithCompletedAuthorization transactionWithCompletedAuthorization = new TransactionWithCompletedAuthorization(
                transactionWithRequestedAuthorization,
                authorizationStatusUpdatedEvent
        );

        TransactionClosed expected = new TransactionClosed(transactionWithCompletedAuthorization, closureSentEvent);

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent2);

        StepVerifier.create(actual)
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldConstructTransactionFromClosureSentEventStreamIgnoringInvalidEvents() {
        EmptyTransaction transaction = new EmptyTransaction();

        String trxId = UUID.randomUUID().toString();
        String rptId = "rptId";
        String paymentToken = "paymentToken";
        String description = "description";
        int amount = 100;

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionActivatedData(
                        description,
                        amount,
                        "email",
                        "faultCode",
                        "faultCodeString",
                        "paymentToken"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionAuthorizationRequestData(
                        amount,
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED
                )
        );

        TransactionClosureSentEvent closureSentEvent = new TransactionClosureSentEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionClosureSendData(
                        ClosePaymentResponseDto.EsitoEnum.OK,
                        TransactionStatusDto.CLOSED
                )
        );

        Flux<TransactionEvent<?>> events = Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationStatusUpdatedEvent,
                closureSentEvent,
                closureSentEvent
        );

        TransactionActivated transactionInitialized = new TransactionActivated(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                ZonedDateTime.parse(transactionActivatedEvent.getCreationDate()),
                TransactionStatusDto.ACTIVATED
        );

        TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = new TransactionWithRequestedAuthorization(
                transactionInitialized,
                authorizationRequestedEvent
        );

        TransactionWithCompletedAuthorization transactionWithCompletedAuthorization = new TransactionWithCompletedAuthorization(
                transactionWithRequestedAuthorization,
                authorizationStatusUpdatedEvent
        );

        TransactionClosed expected = new TransactionClosed(transactionWithCompletedAuthorization, closureSentEvent);

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent2);

        StepVerifier.create(actual)
                .expectNext(expected)
                .verifyComplete();
    }
}
