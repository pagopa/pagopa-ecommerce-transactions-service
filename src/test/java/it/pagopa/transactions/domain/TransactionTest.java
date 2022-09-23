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
        TransactionStatusDto status = TransactionStatusDto.INITIALIZED;
        Email email = new Email("foo@example.com");

        TransactionInitialized transaction = new TransactionInitialized(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                email,
                status);

        assertEquals(transaction.getPaymentToken(), paymentToken);
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

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent);

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
        String email = "foo@example.com";
        TransactionInitEvent event = new TransactionInitEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionInitData(
                        description,
                        amount,
                        email,
                        "faultCode",
                        "faultCodeString"
                ));

        Flux<TransactionEvent<?>> events = Flux.just(event);

        TransactionInitialized expected = new TransactionInitialized(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                new Email(email),
                ZonedDateTime.parse(event.getCreationDate()),
                TransactionStatusDto.INITIALIZED
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent);

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
        String email = "foo@example.com";
        TransactionInitEvent event = new TransactionInitEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionInitData(
                        description,
                        amount,
                        "foo@example.com",
                        "faultCode",
                        "faultCodeString"
                ));

        Flux<TransactionEvent<?>> events = Flux.just(event, event);

        TransactionInitialized expected = new TransactionInitialized(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                new Email(email),
                ZonedDateTime.parse(event.getCreationDate()),
                TransactionStatusDto.INITIALIZED
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent);

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
        String email = "foo@example.com";

        TransactionInitEvent transactionInitEvent = new TransactionInitEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionInitData(
                        description,
                        amount,
                        email,
                        "faultCode",
                        "faultCodeString"
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

        Flux<TransactionEvent<?>> events = Flux.just(transactionInitEvent, authorizationRequestedEvent);

        TransactionInitialized transactionInitialized = new TransactionInitialized(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                new Email(email),
                ZonedDateTime.parse(transactionInitEvent.getCreationDate()),
                TransactionStatusDto.INITIALIZED
        );

        TransactionWithRequestedAuthorization expected = new TransactionWithRequestedAuthorization(
                transactionInitialized.withStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED),
                authorizationRequestedEvent
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent);

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
        String email = "foo@example.com";

        TransactionInitEvent transactionInitEvent = new TransactionInitEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionInitData(
                        description,
                        amount,
                        email,
                        "faultCode",
                        "faultCodeString"
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

        Flux<TransactionEvent<?>> events = Flux.just(transactionInitEvent, authorizationRequestedEvent, authorizationRequestedEvent);

        TransactionInitialized transactionInitialized = new TransactionInitialized(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                new Email(email),
                ZonedDateTime.parse(transactionInitEvent.getCreationDate()),
                TransactionStatusDto.INITIALIZED
        );

        TransactionWithRequestedAuthorization expected = new TransactionWithRequestedAuthorization(
                transactionInitialized.withStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED),
                authorizationRequestedEvent
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent);

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
        String email = "foo@example.com";

        TransactionInitEvent transactionInitEvent = new TransactionInitEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionInitData(
                        description,
                        amount,
                        email,
                        "faultCode",
                        "faultCodeString"
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

        Flux<TransactionEvent<?>> events = Flux.just(transactionInitEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent);

        TransactionInitialized transactionInitialized = new TransactionInitialized(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                new Email(email),
                ZonedDateTime.parse(transactionInitEvent.getCreationDate()),
                TransactionStatusDto.INITIALIZED
        );

        TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = new TransactionWithRequestedAuthorization(
                transactionInitialized.withStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED),
                authorizationRequestedEvent
        );

        TransactionWithCompletedAuthorization expected = new TransactionWithCompletedAuthorization(
                transactionWithRequestedAuthorization.withStatus(TransactionStatusDto.AUTHORIZED),
                authorizationStatusUpdatedEvent
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent);

        System.out.println();

        StepVerifier.create(actual)
                .expectNextMatches(t -> t.equals(expected))
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
        String email = "foo@example.com";

        TransactionInitEvent transactionInitEvent = new TransactionInitEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionInitData(
                        description,
                        amount,
                        email,
                        "faultCode",
                        "faultCodeString"
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

        Flux<TransactionEvent<?>> events = Flux.just(transactionInitEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent, authorizationStatusUpdatedEvent);

        TransactionInitialized transactionInitialized = new TransactionInitialized(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                new Email(email),
                ZonedDateTime.parse(transactionInitEvent.getCreationDate()),
                TransactionStatusDto.INITIALIZED
        );

        TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = new TransactionWithRequestedAuthorization(
                transactionInitialized.withStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED),
                authorizationRequestedEvent
        );

        TransactionWithCompletedAuthorization expected = new TransactionWithCompletedAuthorization(
                transactionWithRequestedAuthorization.withStatus(TransactionStatusDto.AUTHORIZED),
                authorizationStatusUpdatedEvent
        );

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent);

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
        String email = "foo@example.com";

        TransactionInitEvent transactionInitEvent = new TransactionInitEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionInitData(
                        description,
                        amount,
                        email,
                        "faultCode",
                        "faultCodeString"
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

        Flux<TransactionEvent<?>> events = Flux.just(transactionInitEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent, closureSentEvent);

        TransactionInitialized transactionInitialized = new TransactionInitialized(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                new Email(email),
                ZonedDateTime.parse(transactionInitEvent.getCreationDate()),
                TransactionStatusDto.INITIALIZED
        );

        TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = new TransactionWithRequestedAuthorization(
                transactionInitialized.withStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED),
                authorizationRequestedEvent
        );

        TransactionWithCompletedAuthorization transactionWithCompletedAuthorization = new TransactionWithCompletedAuthorization(
                transactionWithRequestedAuthorization.withStatus(TransactionStatusDto.AUTHORIZED),
                authorizationStatusUpdatedEvent
        );

        TransactionClosed expected = new TransactionClosed(transactionWithCompletedAuthorization.withStatus(TransactionStatusDto.CLOSED), closureSentEvent);

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent);

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
        String email = "foo@example.com";

        TransactionInitEvent transactionInitEvent = new TransactionInitEvent(
                trxId,
                rptId,
                paymentToken,
                new TransactionInitData(
                        description,
                        amount,
                        email,
                        "faultCode",
                        "faultCodeString"
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
                transactionInitEvent,
                authorizationRequestedEvent,
                authorizationStatusUpdatedEvent,
                closureSentEvent,
                closureSentEvent
        );

        TransactionInitialized transactionInitialized = new TransactionInitialized(
                new TransactionId(UUID.fromString(trxId)),
                new PaymentToken(paymentToken),
                new RptId(rptId),
                new TransactionDescription(description),
                new TransactionAmount(amount),
                new Email(email),
                ZonedDateTime.parse(transactionInitEvent.getCreationDate()),
                TransactionStatusDto.INITIALIZED
        );

        TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = new TransactionWithRequestedAuthorization(
                transactionInitialized.withStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED),
                authorizationRequestedEvent
        );

        TransactionWithCompletedAuthorization transactionWithCompletedAuthorization = new TransactionWithCompletedAuthorization(
                transactionWithRequestedAuthorization.withStatus(TransactionStatusDto.AUTHORIZED),
                authorizationStatusUpdatedEvent
        );

        TransactionClosed expected = new TransactionClosed(transactionWithCompletedAuthorization.withStatus(TransactionStatusDto.CLOSED), closureSentEvent);

        Mono<Transaction> actual = events.reduce(transaction, Transaction::applyEvent);

        StepVerifier.create(actual)
                .expectNextMatches(t -> t.equals(expected))
                .verifyComplete();
    }
}
