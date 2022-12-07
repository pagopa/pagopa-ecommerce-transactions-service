package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestData;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.domain.TransactionActivated;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

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
            log.warn("Invalid state transition: requested authorization for transaction {} from status {}",
                    transaction.getTransactionActivatedData().getPaymentToken(), transaction.getStatus());
            return Mono.error(new AlreadyProcessedException(transaction.getRptId()));
        }

        return paymentGatewayClient.requestGeneralAuthorization(command.getData())
                .flatMap(authResponse -> {
                    log.info("Logging authorization event for rpt id {}", transaction.getRptId().value());

                    Mono<Tuple2<String,String>> monoPostePay = Mono.just(authResponse.getT1()).filter(pPayAuth -> pPayAuth.isPresent())
                            .switchIfEmpty(Mono.empty())
                            .flatMap(pPay -> Mono.zip(Mono.just(pPay.get().getRequestId()),Mono.just(pPay.get().getUrlRedirect())));

                    Mono<Tuple2<String,String>> monoXPay = Mono.just(authResponse.getT2()).filter(xPayAuth -> xPayAuth.isPresent())
                            .switchIfEmpty(Mono.empty())
                            .flatMap(xPay -> Mono.zip(Mono.just(xPay.get().getRequestId()),Mono.just(xPay.get().getUrlRedirect())));

                    return monoPostePay.switchIfEmpty(monoXPay)
                            .flatMap(tuple2 -> {
                                TransactionAuthorizationRequestedEvent authorizationEvent = new TransactionAuthorizationRequestedEvent(
                                        transaction.getTransactionId().value().toString(),
                                        transaction.getRptId().value(),
                                        transaction.getTransactionActivatedData().getPaymentToken(),
                                        new TransactionAuthorizationRequestData(
                                                command.getData().transaction().getAmount().value(),
                                                command.getData().fee(),
                                                command.getData().paymentInstrumentId(),
                                                command.getData().pspId(),
                                                command.getData().paymentTypeCode(),
                                                command.getData().brokerName(),
                                                command.getData().pspChannelCode(),
                                                command.getData().paymentMethodName(),
                                                command.getData().pspBusinessName(),
                                                tuple2.getT1()));

                                return transactionEventStoreRepository.save(authorizationEvent)
                                        .thenReturn(tuple2)
                                        .map(auth -> new RequestAuthorizationResponseDto()
                                                .authorizationUrl(tuple2.getT2())
                                                .authorizationRequestId(tuple2.getT1()));
                            });
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
