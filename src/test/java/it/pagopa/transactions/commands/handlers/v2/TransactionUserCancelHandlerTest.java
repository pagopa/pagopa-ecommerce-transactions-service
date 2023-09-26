package it.pagopa.transactions.commands.handlers.v2;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.storage.queue.models.SendMessageResult;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.domain.v2.TransactionEventCode;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.queues.TracingUtilsTests;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransactionUserCancelHandlerTest {

    private it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler transactionUserCancelHandler;
    @Mock
    private TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository;

    @Mock
    private QueueAsyncClient transactionUserCancelQueueClient;

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);
    private final TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository, "3020");

    private final int transientQueueEventsTtlSeconds = 30;

    private final TracingUtils tracingUtils = TracingUtilsTests.getMock();

    @Captor
    private ArgumentCaptor<Duration> durationCaptor;

    @BeforeEach
    private void init() {
        transactionUserCancelHandler = new TransactionUserCancelHandler(
                transactionEventUserCancelStoreRepository,
                transactionUserCancelQueueClient,
                transactionsUtils,
                transientQueueEventsTtlSeconds,
                tracingUtils
        );
    }

    @Test
    void shouldSaveCancelEvent() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionUserCancelCommand transactionUserCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(transactionId)
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));

        Mockito.when(transactionEventUserCancelStoreRepository.save(any()))
                .thenAnswer(a -> Mono.just(a.getArgument(0)));

        Mockito.when(
                transactionUserCancelQueueClient
                        .sendMessageWithResponse(any(), any(), durationCaptor.capture())
        )
                .thenReturn(queueSuccessfulResponse());
        /*
         * TEST EXECUTION
         */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .consumeNextWith(
                        next -> {
                            assertEquals(
                                    TransactionEventCode.TRANSACTION_USER_CANCELED_EVENT.toString(),
                                    next.getEventCode()
                            );
                        }
                )
                .verifyComplete();

        verify(transactionEventUserCancelStoreRepository, times(1)).save(any());
        verify(transactionUserCancelQueueClient, times(1)).sendMessageWithResponse(any(), any(), any());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationCaptor.getValue());
    }

    @Test
    void shouldSaveCancelEventWithError() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionUserCancelCommand transactionUserCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(transactionId)
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));

        Mockito.when(transactionEventUserCancelStoreRepository.save(any()))
                .thenReturn(Mono.error(new RuntimeException()));

        /* TEST EXECUTION */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .expectError(RuntimeException.class)
                .verify();

        verify(transactionEventUserCancelStoreRepository, times(1)).save(any());
        verify(transactionUserCancelQueueClient, times(0)).sendMessageWithResponse(any(), any(), any());
    }

    @Test
    void shouldSaveCancelEventWithErrorQueue() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionUserCancelCommand transactionUserCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(transactionId)
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));

        Mockito.when(transactionEventUserCancelStoreRepository.save(any()))
                .thenAnswer(a -> Mono.just(a.getArgument(0)));

        Mockito.when(
                transactionUserCancelQueueClient
                        .sendMessageWithResponse(any(), any(), durationCaptor.capture())
        )
                .thenReturn(Mono.error(new RuntimeException()));

        /* TEST EXECUTION */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .expectError(RuntimeException.class)
                .verify();

        verify(transactionEventUserCancelStoreRepository, times(1)).save(any());
        verify(transactionUserCancelQueueClient, times(1)).sendMessageWithResponse(any(), any(), any());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationCaptor.getValue());
    }

    @Test
    void shouldSaveCancelEventWithErrorTransactionNotFound() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionUserCancelCommand transactionUserCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(transactionId)
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId))
                .thenReturn(Flux.empty());

        /* TEST EXECUTION */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .expectError(TransactionNotFoundException.class)
                .verify();

        verify(transactionEventUserCancelStoreRepository, times(0)).save(any());
        verify(transactionUserCancelQueueClient, times(0)).sendMessageWithResponse(any(), any(), any());
    }

    @Test
    void shouldSaveCancelEventWithErrorAlreadyProcessedException() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionUserCancelCommand transactionUserCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(transactionId)
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(),
                                TransactionTestUtils.transactionUserCanceledEvent()
                        )
                );

        /* TEST EXECUTION */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .expectError(AlreadyProcessedException.class)
                .verify();

        verify(transactionEventUserCancelStoreRepository, times(0)).save(any());
        verify(transactionUserCancelQueueClient, times(0)).sendMessageWithResponse(any(), any(), any());
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
