package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.cosmos.implementation.BadRequestException;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.v1.Transaction;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.List;

@Component
@Slf4j
public class TransactionRequestAuthorizationHandler
        extends BaseHandler<TransactionRequestAuthorizationCommand, Mono<RequestAuthorizationResponseDto>> {

    private final PaymentGatewayClient paymentGatewayClient;

    private final TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;

    private final QueueAsyncClient queueAsyncClient;

    private final String queueVisibilityTimeout;

    @Autowired
    public TransactionRequestAuthorizationHandler(
            TransactionsEventStoreRepository<Object> eventStoreRepository,
            PaymentGatewayClient paymentGatewayClient,
            TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository,
            QueueAsyncClient queueAsyncClient,
            @Value(
                "${azurestorage.queues.transactionauthrequestedtevents.visibilityTimeout}"
            ) String queueVisibilityTimeout
    ) {
        super(eventStoreRepository);
        this.paymentGatewayClient = paymentGatewayClient;
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.queueAsyncClient = queueAsyncClient;
        this.queueVisibilityTimeout = queueVisibilityTimeout;
    }

    @Override
    public Mono<RequestAuthorizationResponseDto> handle(TransactionRequestAuthorizationCommand command) {

        Mono<Transaction> transaction = replayTransactionEvents(
                command.getData().transaction().getTransactionId().value()
        );
        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(
                        t -> log.warn(
                                "Invalid state transition: requested authorization for transaction {} from status {}",
                                t.getTransactionId(),
                                t.getStatus()
                        )
                ).flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));
        Mono<TransactionActivated> transactionActivated = transaction
                .cast(BaseTransaction.class)
                .filter(t -> t.getStatus() == TransactionStatusDto.ACTIVATED)
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionActivated.class);

        var monoPostePay = Mono.just(command.getData())
                .flatMap(
                        authorizationRequestData -> paymentGatewayClient
                                .requestPostepayAuthorization(authorizationRequestData)
                )
                .map(
                        postePayAuthResponseEntityDto -> Tuples.of(
                                postePayAuthResponseEntityDto.getRequestId(),
                                postePayAuthResponseEntityDto.getUrlRedirect()
                        )
                );

        var monoXPay = Mono.just(command.getData())
                .flatMap(
                        authorizationRequestData -> paymentGatewayClient
                                .requestXPayAuthorization(authorizationRequestData)
                )
                .map(
                        xPayAuthResponseEntityDto -> Tuples.of(
                                xPayAuthResponseEntityDto.getRequestId(),
                                xPayAuthResponseEntityDto.getUrlRedirect()
                        )
                );

        var monoVPOS = Mono.just(command.getData())
                .flatMap(
                        authorizationRequestData -> paymentGatewayClient
                                .requestCreditCardAuthorization(authorizationRequestData)
                )
                .map(
                        creditCardAuthResponseDto -> Tuples.of(
                                creditCardAuthResponseDto.getRequestId(),
                                creditCardAuthResponseDto.getUrlRedirect()
                        )
                );

        List<Mono<Tuple2<String, String>>> gatewayRequests = List.of(monoPostePay, monoXPay, monoVPOS);

        Mono<Tuple2<String, String>> gatewayAttempts = gatewayRequests
                .stream()
                .reduce(
                        (
                         pipeline,
                         candidateStep
                        ) -> pipeline.switchIfEmpty(candidateStep)
                ).orElse(Mono.empty());

        return transactionActivated
                .flatMap(
                        t -> gatewayAttempts.switchIfEmpty(Mono.error(new BadRequestException("No gateway matched")))
                                .flatMap(tuple2 -> {
                                    log.info(
                                            "Logging authorization event for transaction id {}",
                                            t.getTransactionId().value()
                                    );
                                    TransactionAuthorizationRequestedEvent authorizationEvent = new TransactionAuthorizationRequestedEvent(
                                            t.getTransactionId().value().toString(),
                                            new TransactionAuthorizationRequestData(
                                                    command.getData().transaction().getPaymentNotices().stream()
                                                            .mapToInt(
                                                                    paymentNotice -> paymentNotice.transactionAmount()
                                                                            .value()
                                                            ).sum(),
                                                    command.getData().fee(),
                                                    command.getData().paymentInstrumentId(),
                                                    command.getData().pspId(),
                                                    command.getData().paymentTypeCode(),
                                                    command.getData().brokerName(),
                                                    command.getData().pspChannelCode(),
                                                    command.getData().paymentMethodName(),
                                                    command.getData().pspBusinessName(),
                                                    tuple2.getT1()
                                            )
                                    );

                                    return transactionEventStoreRepository.save(authorizationEvent)
                                            .doOnNext(
                                                    event -> queueAsyncClient.sendMessageWithResponse(
                                                            BinaryData.fromObject(event),
                                                            Duration.ofSeconds(
                                                                    Integer.parseInt(queueVisibilityTimeout)
                                                            ),
                                                            null
                                                    ).subscribe(
                                                            response -> log.debug(
                                                                    "Message {} expires at {}",
                                                                    response.getValue().getMessageId(),
                                                                    response.getValue().getExpirationTime()
                                                            ),
                                                            error -> log.error(error.toString()),
                                                            () -> log.debug("Complete enqueuing the message!")
                                                    )
                                            )
                                            .thenReturn(tuple2)
                                            .map(
                                                    auth -> new RequestAuthorizationResponseDto()
                                                            .authorizationUrl(tuple2.getT2())
                                                            .authorizationRequestId(tuple2.getT1())
                                            );
                                })
                                .doOnError(BadRequestException.class, error -> log.error(error.getMessage()))
                );
    }
}
