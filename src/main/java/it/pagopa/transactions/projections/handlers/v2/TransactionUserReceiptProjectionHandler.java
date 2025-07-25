package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component(TransactionUserReceiptProjectionHandler.QUALIFIER_NAME)
@Slf4j
public class TransactionUserReceiptProjectionHandler
        implements
        ProjectionHandler<it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptRequestedEvent, Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction>> {

    public static final String QUALIFIER_NAME = "transactionUserReceiptProjectionHandlerV2";

    private TransactionsViewRepository transactionsViewRepository;
    private final boolean transactionsviewUpdateEnabled;

    @Autowired
    public TransactionUserReceiptProjectionHandler(
            TransactionsViewRepository transactionsViewRepository,
            @Value("${transactionsview.update.enabled}") boolean transactionsviewUpdateEnabled
    ) {
        this.transactionsViewRepository = transactionsViewRepository;
        this.transactionsviewUpdateEnabled = transactionsviewUpdateEnabled;
    }

    @Override
    public Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction> handle(
                                                                             it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptRequestedEvent data
    ) {
        return transactionsViewRepository.findById(data.getTransactionId())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(data.getTransactionId())))
                .cast(it.pagopa.ecommerce.commons.documents.v2.Transaction.class)
                .flatMap(transactionDocument -> conditionallySaveTransactionView(transactionDocument, data));
    }

    private Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction> conditionallySaveTransactionView(
                                                                                                        it.pagopa.ecommerce.commons.documents.v2.Transaction transactionDocument,
                                                                                                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptRequestedEvent data
    ) {
        TransactionStatusDto newStatus = TransactionStatusDto.NOTIFICATION_REQUESTED;
        transactionDocument.setStatus(newStatus);
        transactionDocument.setSendPaymentResultOutcome(data.getData().getResponseOutcome());
        if (transactionsviewUpdateEnabled) {
            return transactionsViewRepository.save(transactionDocument);
        } else {
            return Mono.just(transactionDocument);
        }
    }
}
