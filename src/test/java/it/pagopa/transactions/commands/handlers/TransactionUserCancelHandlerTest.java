package it.pagopa.transactions.commands.handlers;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.SendMessageResult;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

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

    private static final int RETRY_TIMEOUT_INTERVAL = 5;

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
        String transactionId = UUID.randomUUID().toString();
        TransactionUserCanceledEvent userCanceledEvent = new TransactionUserCanceledEvent(
                transactionId
        );

        TransactionUserCancelCommand transactionUserCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(UUID.fromString(transactionId))
        );

        /* PRECONDITION */
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));

        Mockito.when(transactionEventUserCancelStoreRepository.save(any()))
                .thenAnswer(el -> Mono.just(el.getArguments()[0]));

        Mockito.when(transactionUserCancelQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any()))
                .thenReturn(queueSuccessfulResponse());

        /* EXECUTION TEST */
        transactionUserCancelHandler.handle(transactionUserCancelCommand).block();

        Mockito.verify(transactionEventUserCancelStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionUserCancelQueueClient, Mockito.times(0))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(userCanceledEvent).toByteBuffer())
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
