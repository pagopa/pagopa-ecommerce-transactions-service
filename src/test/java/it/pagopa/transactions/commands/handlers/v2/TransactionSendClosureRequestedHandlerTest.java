package it.pagopa.transactions.commands.handlers.v2;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.storage.queue.models.SendMessageResult;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.*;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionEventCode;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.queues.TracingUtilsTests;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.transactions.commands.TransactionClosureRequestCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
class TransactionSendClosureRequestedHandlerTest {

    private it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler transactionSendClosureRequestHandler;

    private TransactionsEventStoreRepository<Void> transactionEventClosureRequestedRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);;

    private QueueAsyncClient transactionSendClosureRequestQueueClient = Mockito.mock(QueueAsyncClient.class);

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository, "3020");

    private final int transientQueueEventsTtlSeconds = 30;

    private final TracingUtils tracingUtils = TracingUtilsTests.getMock();

    @Captor
    private ArgumentCaptor<Duration> durationCaptor;

    @BeforeEach
    private void init() {
        transactionSendClosureRequestHandler = new TransactionSendClosureRequestHandler(
                transactionEventClosureRequestedRepository,
                transactionSendClosureRequestQueueClient,
                transientQueueEventsTtlSeconds,
                transactionsUtils,
                tracingUtils
        );
    }

    @Test
    void shouldSaveClosureRequestedEvent() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionClosureRequestCommand transactionClosureRequestCommand = new TransactionClosureRequestCommand(
                null,
                new TransactionId(transactionId)
        );

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent1 = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();
        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(new NpgTransactionGatewayAuthorizationData());

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent1, authorizationCompletedEvent));

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId))
                .thenReturn(events);

        Mockito.when(transactionEventClosureRequestedRepository.save(any()))
                .thenAnswer(a -> Mono.just(a.getArgument(0)));

        Mockito.when(
                transactionSendClosureRequestQueueClient
                        .sendMessageWithResponse(any(), any(), durationCaptor.capture())
        )
                .thenReturn(queueSuccessfulResponse());
        /*
         * TEST EXECUTION
         */
        StepVerifier.create(transactionSendClosureRequestHandler.handle(transactionClosureRequestCommand))
                .consumeNextWith(
                        next -> {
                            assertEquals(
                                    TransactionEventCode.TRANSACTION_CLOSURE_REQUESTED_EVENT.toString(),
                                    next.getEventCode()
                            );
                        }
                )
                .verifyComplete();

        verify(transactionEventClosureRequestedRepository, times(1)).save(any());
        verify(transactionSendClosureRequestQueueClient, times(1)).sendMessageWithResponse(any(), any(), any());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationCaptor.getValue());
    }

    @Test
    void shouldNotSaveClosureRequestedEventWithErrorTransactionNotFound() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionClosureRequestCommand transactionClosureRequestCommand = new TransactionClosureRequestCommand(
                null,
                new TransactionId(transactionId)
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId))
                .thenReturn(Flux.empty());

        /* TEST EXECUTION */
        StepVerifier.create(transactionSendClosureRequestHandler.handle(transactionClosureRequestCommand))
                .expectError(TransactionNotFoundException.class)
                .verify();

        verify(transactionEventClosureRequestedRepository, times(0)).save(any());
        verify(transactionSendClosureRequestQueueClient, times(0)).sendMessageWithResponse(any(), any(), any());
    }

    @Test
    void shouldNotSaveClosureRequestedEventWithErrorAlreadyProcessedException() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        TransactionClosureRequestCommand transactionClosureRequestCommand = new TransactionClosureRequestCommand(
                null,
                new TransactionId(transactionId)
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(),
                                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                                TransactionTestUtils.transactionAuthorizationCompletedEvent(
                                        new NpgTransactionGatewayAuthorizationData()
                                ),
                                TransactionTestUtils.transactionClosureRequestedEvent()
                        )
                );

        /* TEST EXECUTION */
        StepVerifier.create(transactionSendClosureRequestHandler.handle(transactionClosureRequestCommand))
                .expectError(AlreadyProcessedException.class)
                .verify();

        verify(transactionEventClosureRequestedRepository, times(0)).save(any());
        verify(transactionSendClosureRequestQueueClient, times(0)).sendMessageWithResponse(any(), any(), any());
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
