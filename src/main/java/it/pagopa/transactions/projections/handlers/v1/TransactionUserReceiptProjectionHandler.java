package it.pagopa.transactions.projections.handlers.v1;

import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component(TransactionUserReceiptProjectionHandler.QUALIFIER_NAME)
@Slf4j
public class TransactionUserReceiptProjectionHandler
        implements
        ProjectionHandler<it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptRequestedEvent, Mono<it.pagopa.ecommerce.commons.documents.v1.Transaction>> {

    public static final String QUALIFIER_NAME = "TransactionUserReceiptProjectionHandlerV1";
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<it.pagopa.ecommerce.commons.documents.v1.Transaction> handle(
                                                                             it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptRequestedEvent data
    ) {
        return transactionsViewRepository.findById(data.getTransactionId())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(data.getTransactionId())))
                .cast(it.pagopa.ecommerce.commons.documents.v1.Transaction.class)
                .flatMap(transactionDocument -> {
                    TransactionStatusDto newStatus = TransactionStatusDto.NOTIFICATION_REQUESTED;
                    transactionDocument.setStatus(newStatus);
                    transactionDocument.setSendPaymentResultOutcome(data.getData().getResponseOutcome());
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
