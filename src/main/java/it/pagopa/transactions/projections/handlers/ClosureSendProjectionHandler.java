package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Optional;

@Component
@Slf4j
public class ClosureSendProjectionHandler
        implements ProjectionHandler<TransactionEvent<TransactionClosureData>, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(TransactionEvent<TransactionClosureData> event) {
        return transactionsViewRepository.findById(event.getTransactionId())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(event.getTransactionId())))
                .cast(it.pagopa.ecommerce.commons.documents.v1.Transaction.class)
                .flatMap(transactionDocument -> {
                    Tuple2<TransactionStatusDto, Optional<TransactionUserReceiptData.Outcome>> viewUpdatedData =
                            switch (event) {
                                case TransactionClosedEvent e -> Tuples.of(TransactionStatusDto.CLOSED,
                                        Optional
                                                .of(e.getData())
                                                .map(data -> switch (data.getResponseOutcome()) {
                                                    case OK -> TransactionUserReceiptData.Outcome.NOT_RECEIVED;
                                                    case KO -> null;
                                                })
                                );
                                case TransactionClosureFailedEvent e ->
                                        Tuples.of(TransactionStatusDto.UNAUTHORIZED, Optional.empty());
                                case default -> throw new IllegalArgumentException("Unexpected event: " + event);
                            };

                    transactionDocument.setStatus(viewUpdatedData.getT1());
                    transactionDocument.setSendPaymentResultOutcome(viewUpdatedData.getT2().orElse(null));
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
