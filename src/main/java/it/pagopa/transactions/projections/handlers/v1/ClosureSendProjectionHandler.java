package it.pagopa.transactions.projections.handlers.v1;

import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Optional;

@Component(ClosureSendProjectionHandler.QUALIFIER_NAME)
@Slf4j
public class ClosureSendProjectionHandler
        implements
        ProjectionHandler<it.pagopa.ecommerce.commons.documents.v1.TransactionEvent<it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData>, Mono<it.pagopa.ecommerce.commons.documents.v1.Transaction>> {

    public static final String QUALIFIER_NAME = "ClosureSendProjectionHandlerV1";
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<it.pagopa.ecommerce.commons.documents.v1.Transaction> handle(it.pagopa.ecommerce.commons.documents.v1.TransactionEvent<it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData> event) {
        return transactionsViewRepository.findById(event.getTransactionId())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(event.getTransactionId())))
                .cast(it.pagopa.ecommerce.commons.documents.v1.Transaction.class)
                .flatMap(transactionDocument -> {
                    Tuple2<TransactionStatusDto, Optional<it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptData.Outcome>> viewUpdatedData =
                            switch (event) {
                                case it.pagopa.ecommerce.commons.documents.v1.TransactionClosedEvent e -> Tuples.of(TransactionStatusDto.CLOSED,
                                        Optional
                                                .of(e.getData())
                                                .map(data -> switch (data.getResponseOutcome()) {
                                                    case OK -> it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptData.Outcome.NOT_RECEIVED;
                                                    case KO -> null;
                                                })
                                );
                                case it.pagopa.ecommerce.commons.documents.v1.TransactionClosureFailedEvent e ->
                                        Tuples.of(TransactionStatusDto.UNAUTHORIZED, Optional.empty());
                                case default -> throw new IllegalArgumentException("Unexpected event: " + event);
                            };

                    transactionDocument.setStatus(viewUpdatedData.getT1());
                    transactionDocument.setSendPaymentResultOutcome(viewUpdatedData.getT2().orElse(null));
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
