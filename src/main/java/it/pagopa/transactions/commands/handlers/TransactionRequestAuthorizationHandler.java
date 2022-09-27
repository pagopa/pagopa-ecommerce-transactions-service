package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestData;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.domain.TransactionInitialized;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionRequestAuthorizationHandler
        implements CommandHandler<TransactionRequestAuthorizationCommand, Mono<RequestAuthorizationResponseDto>> {
    @Autowired
    private PaymentGatewayClient paymentGatewayClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;

    @Autowired
    QueueAsyncClient queueAsyncClient;

    @Value("${azurestorage.queues.transactionauthrequestedtevents.visibilityTimeout}")
    String queueVisibilityTimeout;

    @Override
    public Mono<RequestAuthorizationResponseDto> handle(TransactionRequestAuthorizationCommand command) {
        TransactionInitialized transaction = command.getData().transaction();

        if (transaction.getStatus() != TransactionStatusDto.ACTIVATED) {
            log.warn("Invalid state transition: requested authorization for transaction {} from status {}",
                    transaction.getPaymentToken().value(), transaction.getStatus());
            return Mono.error(new AlreadyProcessedException(transaction.getRptId()));
        }

        return paymentGatewayClient.requestAuthorization(command.getData())
                .flatMap(auth -> {
                    log.info("Logging authorization event for rpt id {}", transaction.getRptId().value());

                    TransactionAuthorizationRequestedEvent authorizationEvent = new TransactionAuthorizationRequestedEvent(
                            transaction.getTransactionId().value().toString(),
                            transaction.getRptId().value(),
                            transaction.getPaymentToken().value(),
                            new TransactionAuthorizationRequestData(
                                    command.getData().transaction().getAmount().value(),
                                    command.getData().fee(),
                                    command.getData().paymentInstrumentId(),
                                    command.getData().pspId(),
                                    command.getData().paymentTypeCode(),
                                    command.getData().brokerName(),
                                    command.getData().pspChannelCode()));

                    return transactionEventStoreRepository.save(authorizationEvent).thenReturn(auth);
                })
                .doOnNext(authorizationEvent -> queueAsyncClient.sendMessageWithResponse(
                        BinaryData.fromObject(authorizationEvent),
                        Duration.ofSeconds(Integer.valueOf(queueVisibilityTimeout)), null).subscribe(
                                response -> log.debug("Message {} expires at {}", response.getValue().getMessageId(),
                                        response.getValue().getExpirationTime()),
                                error -> log.error(error.toString()),
                                () -> log.debug("Complete enqueuing the message!")));
    }
}
