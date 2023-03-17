package it.pagopa.transactions.commands.handlers;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.SendMessageResult;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
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
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
public class TransactionUserCancelHandlerTest {

    private TransactionUserCancelHandler transactionUserCancelHandler;

    @Mock
    private TransactionsEventStoreRepository<Object> eventStoreRepository;
    @Mock
    private TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository;

    @Mock
    private QueueAsyncClient transactionUserCancelQueueClient;

    @BeforeEach
    private void init() {
        transactionUserCancelHandler = new TransactionUserCancelHandler(
                eventStoreRepository,
                transactionEventUserCancelStoreRepository,
                transactionUserCancelQueueClient
        );
    }

    @Test
    void shouldSaveAuthorizationEvent() {
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
        /* EXECUTION TEST */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .consumeNextWith(
                        next -> {
                            assertEquals(TransactionEventCode.TRANSACTION_USER_CANCELED_EVENT, next.getEventCode());
                        }
                )
                .verifyComplete();
    }

    @Test
    void shouldSaveAuthorizationEventWithError() {
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

        /* EXECUTION TEST */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void shouldSaveAuthorizationEventWithErrorQueue() {
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

        /* EXECUTION TEST */
        StepVerifier.create(transactionUserCancelHandler.handle(transactionUserCancelCommand))
                .expectError(RuntimeException.class)
                .verify();
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
