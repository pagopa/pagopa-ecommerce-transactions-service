package it.pagopa.transactions.projections.handlers;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptAddErrorEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptAddedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptData;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import javax.annotation.Nonnull;

@Component
@Slf4j
public class TransactionUserReceiptProjectionHandler
        implements ProjectionHandler<Either<Mono<TransactionUserReceiptAddErrorEvent>, Mono<TransactionUserReceiptAddedEvent>>, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(Either<Mono<TransactionUserReceiptAddErrorEvent>, Mono<TransactionUserReceiptAddedEvent>> userReceiptEither) {
        return userReceiptEither.fold(
                userReceiptErrorEvent -> userReceiptErrorEvent.map(
                        event -> Tuples.of(event.getTransactionId(), TransactionStatusDto.NOTIFICATION_ERROR)
                ),
                userReceiptSuccessEvent -> userReceiptSuccessEvent.map(
                        event -> Tuples.of(event.getTransactionId(), statusFromOutcome(event.getData().getResponseOutcome()))
                )
        ).flatMap(args -> {
                    String transactionId = args.getT1();
                    TransactionStatusDto newStatus = args.getT2();
                    return transactionsViewRepository.findByTransactionId(transactionId)
                            .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                            .flatMap(transactionDocument -> {
                                log.info("Updating transaction with id: {} to status: {}", transactionId, newStatus);
                                transactionDocument.setStatus(newStatus);
                                return transactionsViewRepository.save(transactionDocument);
                            });
                }
        );
    }

    @Nonnull
    private static TransactionStatusDto statusFromOutcome(
                                                          @Nonnull TransactionUserReceiptData.Outcome userReceiptAddResponseOutcome
    ) {
        return switch (userReceiptAddResponseOutcome) {
            case OK -> TransactionStatusDto.NOTIFIED_OK;
            case KO -> TransactionStatusDto.REFUND_REQUESTED;
        };
    }
}
