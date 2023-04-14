package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.v1.TransactionId;
import it.pagopa.ecommerce.commons.domain.v1.TransactionWithRequestedAuthorization;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

class TransactionsUtilsTest {

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository, "3020");

    @Test
    void shouldReduceTransactionCorrectly() {
        TransactionId transactionId = new TransactionId(UUID.fromString(TransactionTestUtils.TRANSACTION_ID));
        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();
        Flux events = Flux.just(transactionActivatedEvent, transactionAuthorizationRequestedEvent);
        given(eventStoreRepository.findByTransactionId(transactionId.value().toString())).willReturn(events);
        StepVerifier.create(transactionsUtils.reduceEvents(transactionId))
                .expectNextMatches(
                        baseTransaction -> baseTransaction instanceof TransactionWithRequestedAuthorization
                                && baseTransaction.getStatus() == TransactionStatusDto.AUTHORIZATION_REQUESTED
                )
                .verifyComplete();
    }

    @Test
    void shouldThrowTransactionNotFoundForNoEventsFoundForTransactionId() {
        TransactionId transactionId = new TransactionId(UUID.fromString(TransactionTestUtils.TRANSACTION_ID));
        given(eventStoreRepository.findByTransactionId(transactionId.value().toString())).willReturn(Flux.empty());
        StepVerifier.create(transactionsUtils.reduceEvents(transactionId))
                .expectErrorMatches(
                        ex -> ex instanceof TransactionNotFoundException transactionNotFoundException
                                && transactionNotFoundException.getPaymentToken()
                                        .equals(transactionId.value().toString())
                )
                .verify();
    }

    @Test
    void shouldConvertAllCommonsStatusCorrectly() {
        for (TransactionStatusDto status : TransactionStatusDto.values()) {
            assertEquals(status.toString(), transactionsUtils.convertEnumeration(status).toString());
        }
    }

}
