package it.pagopa.transactions.commands.handlers;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.SendMessageResult;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
import it.pagopa.ecommerce.commons.domain.v1.TransactionId;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class TransactionUserCancelHandlerTest {

    private TransactionUserCancelHandler transactionUserCancelHandler;
    @Mock
    private TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository;

    @Mock
    private QueueAsyncClient transactionUserCancelQueueClient;

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);
    private final TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository, "3020");

    @BeforeEach
    private void init() {
        transactionUserCancelHandler = new TransactionUserCancelHandler(
                transactionEventUserCancelStoreRepository,
                transactionUserCancelQueueClient,
                transactionsUtils
        );
    }

    @Test
    void shouldSaveCancelEvent() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionUserCancelCommand transactionUserCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(UUID.fromString(transactionId))
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));

        Mockito.when(transactionEventUserCancelStoreRepository.save(any()))
                .thenAnswer(a -> Mono.just(a.getArgument(0)));

        Mockito.when(transactionUserCancelQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any()))
                .thenReturn(queueSuccessfulResponse());
        /*
         * TEST EXECUTION
         */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .consumeNextWith(
                        next -> {
                            assertEquals(TransactionEventCode.TRANSACTION_USER_CANCELED_EVENT, next.getEventCode());
                        }
                )
                .verifyComplete();

        verify(transactionEventUserCancelStoreRepository, times(1)).save(any());
        verify(transactionUserCancelQueueClient, times(1)).sendMessageWithResponse(any(BinaryData.class), any(), any());
    }

    @Test
    void shouldSaveCancelEventWithError() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionUserCancelCommand transactionUserCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(UUID.fromString(transactionId))
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));

        Mockito.when(transactionEventUserCancelStoreRepository.save(any()))
                .thenReturn(Mono.error(new RuntimeException()));

        /* TEST EXECUTION */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .expectError(RuntimeException.class)
                .verify();

        verify(transactionEventUserCancelStoreRepository, times(1)).save(any());
        verify(transactionUserCancelQueueClient, times(0)).sendMessageWithResponse(any(BinaryData.class), any(), any());
    }

    @Test
    void shouldSaveCancelEventWithErrorQueue() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionUserCancelCommand transactionUserCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(UUID.fromString(transactionId))
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));

        Mockito.when(transactionEventUserCancelStoreRepository.save(any()))
                .thenAnswer(a -> Mono.just(a.getArgument(0)));

        Mockito.when(transactionUserCancelQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any()))
                .thenReturn(Mono.error(new RuntimeException()));

        /* TEST EXECUTION */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .expectError(RuntimeException.class)
                .verify();

        verify(transactionEventUserCancelStoreRepository, times(1)).save(any());
        verify(transactionUserCancelQueueClient, times(1)).sendMessageWithResponse(any(BinaryData.class), any(), any());
    }

    @Test
    void shouldSaveCancelEventWithErrorTransactionNotFound() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionUserCancelCommand transactionUserCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(UUID.fromString(transactionId))
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId))
                .thenReturn(Flux.empty());

        /* TEST EXECUTION */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .expectError(TransactionNotFoundException.class)
                .verify();

        verify(transactionEventUserCancelStoreRepository, times(0)).save(any());
        verify(transactionUserCancelQueueClient, times(0)).sendMessageWithResponse(any(BinaryData.class), any(), any());
    }

    @Test
    void shouldSaveCancelEventWithErrorAlreadyProcessedException() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionUserCancelCommand transactionUserCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(UUID.fromString(transactionId))
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId))
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
        verify(transactionUserCancelQueueClient, times(0)).sendMessageWithResponse(any(BinaryData.class), any(), any());
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
