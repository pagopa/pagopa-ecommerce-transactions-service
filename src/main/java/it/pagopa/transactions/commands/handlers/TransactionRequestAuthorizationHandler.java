package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.cosmos.implementation.BadRequestException;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.NoticeCode;
import it.pagopa.ecommerce.commons.documents.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.TransactionActivated;
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
        TransactionActivated transaction = command.getData().transaction();

        if (transaction.getStatus() != TransactionStatusDto.ACTIVATED) {
            log.warn(
                    "Invalid state transition: requested authorization for transaction {} from status {}",
                    transaction.getTransactionId(),
                    transaction.getStatus()
            );
            return Mono.error(new AlreadyProcessedException(transaction.getTransactionId()));
        }

        var monoPostePay = Mono.just(command.getData())
                .flatMap(d -> paymentGatewayClient.requestPostepayAuthorization(d))
                .map(p -> Tuples.of(p.getRequestId(), p.getUrlRedirect()));

        var monoXPay = Mono.just(command.getData())
                .flatMap(d -> paymentGatewayClient.requestXPayAuthorization(d))
                .map(p -> Tuples.of(p.getRequestId(), p.getUrlRedirect()));

        List<Mono<Tuple2<String, String>>> gatewayRequests = List.of(monoPostePay, monoXPay);

        Mono<Tuple2<String, String>> gatewayAttempts = gatewayRequests
                .stream()
                .reduce(
                        (
                         pipeline,
                         candidateStep
                        ) -> pipeline.switchIfEmpty(candidateStep)
                ).orElse(Mono.empty());

        return gatewayAttempts.switchIfEmpty(Mono.error(new BadRequestException("No gateway matched")))
                .flatMap(tuple2 -> {
                    log.info("Logging authorization event for transaction id {}", transaction.getTransactionId().value());

                    TransactionAuthorizationRequestedEvent authorizationEvent = new TransactionAuthorizationRequestedEvent(
                            transaction.getTransactionId().value().toString(),
                            transaction.getNoticeCodes().stream().map(
                                    noticeCode -> new NoticeCode(
                                            noticeCode.paymentToken().value(),
                                            noticeCode.rptId().value(),
                                            noticeCode.transactionDescription().value(),
                                            noticeCode.transactionAmount().value(),
                                            noticeCode.paymentContextCode().value()
                                    )
                            ).toList(),
                            new TransactionAuthorizationRequestData(
                                    command.getData().transaction().getNoticeCodes().stream()
                                            .mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum(),
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
                            .thenReturn(tuple2)
                            .map(
                                    auth -> new RequestAuthorizationResponseDto()
                                            .authorizationUrl(tuple2.getT2())
                                            .authorizationRequestId(tuple2.getT1())
                            );
                })
                .doOnError(BadRequestException.class, error -> log.error(error.getMessage()))
                .doOnNext(
                        authorizationEvent -> queueAsyncClient.sendMessageWithResponse(
                                BinaryData.fromObject(authorizationEvent),
                                Duration.ofSeconds(Integer.parseInt(queueVisibilityTimeout)),
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
                );
    }
}
