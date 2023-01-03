package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.Transaction;
import it.pagopa.ecommerce.commons.documents.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.TransactionActivationRequested;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestsInfoRepository;
import it.pagopa.transactions.client.NodoPerPM;
import it.pagopa.transactions.commands.TransactionActivateResultCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@Slf4j
public class TransactionActivateResultHandler
        implements CommandHandler<TransactionActivateResultCommand, Mono<TransactionActivatedEvent>> {

    @Autowired
    private TransactionsEventStoreRepository<TransactionActivatedData> transactionEventStoreRepository;

    @Autowired
    private NodoPerPM nodoPerPM;

    @Autowired
    private PaymentRequestsInfoRepository paymentRequestsInfoRepository;

    @Autowired
    @Qualifier("transactionActivatedQueueAsyncClient")
    QueueAsyncClient transactionActivatedQueueAsyncClient;

    @Value("${payment.token.validity}")
    Integer paymentTokenTimeout;

    @Override
    public Mono<TransactionActivatedEvent> handle(TransactionActivateResultCommand command) {

        final TransactionActivationRequested transactionActivationRequested = command.getData()
                .transactionActivationRequested();

        final String transactionId = transactionActivationRequested.getTransactionId().value()
                .toString();
        final Transaction.OriginType originType = transactionActivationRequested.getOriginType();
        return Mono.just(command)
                .filter(
                        commandData -> commandData.getData().transactionActivationRequested()
                                .getStatus() == TransactionStatusDto.ACTIVATION_REQUESTED
                )
                .switchIfEmpty(
                        Mono.error(
                                new AlreadyProcessedException(
                                        command.getData().transactionActivationRequested().getTransactionId()
                                )
                        )
                )
                .flatMap(
                        commandData -> {
                            final String paymentToken = commandData.getData().activationResultData().getPaymentToken();

                            return nodoPerPM
                                    .chiediInformazioniPagamento(paymentToken)
                                    .doOnError(
                                            throwable -> {
                                                log.error(
                                                        "chiediInformazioniPagamento failed for paymentToken {}",
                                                        paymentToken
                                                );
                                                throw new TransactionNotFoundException(
                                                        "chiediInformazioniPagamento failed for paymentToken "
                                                                + paymentToken
                                                );
                                            }
                                    )
                                    .doOnSuccess(
                                            a -> log.info(
                                                    "chiediInformazioniPagamento succeded for paymentToken {}",
                                                    paymentToken
                                            )
                                    );
                        }
                )
                .flatMap(
                        informazioniPagamentoDto -> paymentRequestsInfoRepository
                                .findById(command.getRptId())
                                .map(Mono::just)
                                .orElseGet(Mono::empty)
                )
                .switchIfEmpty(
                        Mono.error(
                                new TransactionNotFoundException(
                                        "Transaction not found for rptID "
                                                + command.getRptId().value()
                                                + " with paymentToken "
                                                + command.getData().activationResultData().getPaymentToken()
                                )
                        )
                )
                .flatMap(
                        paymentRequestInfo -> {
                            log.info(
                                    "paymentRequestsInfoRepository findById info for rptID {} succeeded",
                                    command.getRptId().value()
                            );
                            // FIXME check why this repository is not a reactive repository
                            return Mono.just(
                                    paymentRequestsInfoRepository.save(
                                            new PaymentRequestInfo(
                                                    paymentRequestInfo.id(),
                                                    paymentRequestInfo.paFiscalCode(),
                                                    paymentRequestInfo.paName(),
                                                    paymentRequestInfo.description(),
                                                    paymentRequestInfo.amount(),
                                                    paymentRequestInfo.dueDate(),
                                                    paymentRequestInfo.isNM3(),
                                                    command.getData().activationResultData().getPaymentToken(),
                                                    paymentRequestInfo.idempotencyKey()
                                            )
                                    )
                            );
                        }
                )
                .flatMap(
                        saved -> {
                            TransactionActivatedData data = new TransactionActivatedData(
                                    transactionActivationRequested.getEmail().value(),
                                    transactionActivationRequested.getPaymentNotices().stream()
                                            .map(
                                                    paymentNotice -> new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                                                            saved.paymentToken(),
                                                            paymentNotice.rptId().value(),
                                                            paymentNotice.transactionDescription().value(),
                                                            paymentNotice.transactionAmount().value(),
                                                            paymentNotice.paymentContextCode().value()
                                                    )
                                            )
                                            .toList(),
                                    null,
                                    null,
                                    originType
                            );

                            TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                                    transactionId,
                                    data
                            );

                            log.info("Saving TransactionActivatedEvent {}", transactionActivatedEvent);
                            return transactionEventStoreRepository
                                    .save(transactionActivatedEvent)
                                    .then(
                                            transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                                                    BinaryData.fromObject(transactionActivatedEvent),
                                                    Duration.ofSeconds(paymentTokenTimeout),
                                                    null
                                            )
                                    )
                                    .then(Mono.just(transactionActivatedEvent))
                                    .doOnError(
                                            exception -> log.error(
                                                    "Error to generate event TRANSACTION_ACTIVATED_EVENT for rptIds {} and transactionId {} - error {}",
                                                    String.join(
                                                            ",",
                                                            transactionActivatedEvent.getData().getPaymentNotices()
                                                                    .stream()
                                                                    .map(PaymentNotice::getRptId).toList()
                                                    ),
                                                    transactionActivatedEvent.getTransactionId(),
                                                    exception.getMessage()
                                            )
                                    )
                                    .doOnNext(
                                            event -> log.info(
                                                    "Generated event TRANSACTION_ACTIVATED_EVENT for rptIds {} and transactionId {}",
                                                    String.join(
                                                            ",",
                                                            event.getData().getPaymentNotices().stream()
                                                                    .map(PaymentNotice::getRptId)
                                                                    .toList()
                                                    ),
                                                    event.getTransactionId()
                                            )
                                    );
                        }
                );
    }
}
