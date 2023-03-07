package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptAddedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptData;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
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
                    TransactionStatusDto newStatus = statusFromOutcome(data.getData().getResponseOutcome());

                    transactionDocument.setStatus(newStatus);
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
                                transactionDocument.getEmail(),
                                null,
                                null,
                                ZonedDateTime.parse(transactionDocument.getCreationDate()),
                                transactionDocument.getClientId()
                        )
                );
    }

    @Nonnull
    private static TransactionStatusDto statusFromOutcome(
                                                          @Nonnull TransactionUserReceiptData.Outcome userReceiptAddResponseOutcome
    ) {
        return switch (userReceiptAddResponseOutcome) {
            case OK -> TransactionStatusDto.NOTIFIED_OK;
            case KO -> TransactionStatusDto.NOTIFIED_KO;
        };
    }
}
