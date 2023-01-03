package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.TransactionUserReceiptAddedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;
import java.util.UUID;

@Component
@Slf4j
public class TransactionUserReceiptProjectionHandler
        implements ProjectionHandler<TransactionUserReceiptAddedEvent, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(TransactionUserReceiptAddedEvent data) {
        return transactionsViewRepository.findById(data.getTransactionId())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(data.getTransactionId())))
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(data.getData().getNewTransactionStatus());
                    return transactionsViewRepository.save(transactionDocument);
                })
                .map(
                        transactionDocument -> new TransactionActivated(
                                new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                                transactionDocument.getPaymentNotices().stream().map(
                                        paymentNotice -> new PaymentNotice(
                                                new PaymentToken(paymentNotice.getPaymentToken()),
                                                new RptId(paymentNotice.getRptId()),
                                                new TransactionAmount(paymentNotice.getAmount()),
                                                new TransactionDescription(paymentNotice.getDescription()),
                                                new PaymentContextCode(paymentNotice.getPaymentContextCode())
                                        )
                                ).toList(),
                                new Email(transactionDocument.getEmail()),
                                null,
                                null,
                                ZonedDateTime.parse(transactionDocument.getCreationDate()),
                                transactionDocument.getStatus(),
                                transactionDocument.getOrigin()
                        )
                );
    }
}
