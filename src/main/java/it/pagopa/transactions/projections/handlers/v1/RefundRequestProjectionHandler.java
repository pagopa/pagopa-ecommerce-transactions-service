package it.pagopa.transactions.projections.handlers.v1;

import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component(RefundRequestProjectionHandler.QUALIFIER_NAME)
@Slf4j
public class RefundRequestProjectionHandler
        implements
        ProjectionHandler<it.pagopa.ecommerce.commons.documents.v1.TransactionRefundRequestedEvent, Mono<it.pagopa.ecommerce.commons.documents.v1.Transaction>> {

    public static final String QUALIFIER_NAME = "RefundRequestProjectionHandlerV1";
    private final TransactionsViewRepository transactionsViewRepository;

    @Autowired
    public RefundRequestProjectionHandler(
            TransactionsViewRepository transactionsViewRepository
    ) {
        this.transactionsViewRepository = transactionsViewRepository;
    }

    @Override
    public Mono<it.pagopa.ecommerce.commons.documents.v1.Transaction> handle(
                                                                             it.pagopa.ecommerce.commons.documents.v1.TransactionRefundRequestedEvent transactionRefundRequestedEvent
    ) {
        return transactionsViewRepository.findById(transactionRefundRequestedEvent.getTransactionId())
                .switchIfEmpty(
                        Mono.error(
                                new TransactionNotFoundException(
                                        transactionRefundRequestedEvent.getTransactionId()
                                )
                        )
                )
                .cast(it.pagopa.ecommerce.commons.documents.v1.Transaction.class)
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(TransactionStatusDto.REFUND_REQUESTED);
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
