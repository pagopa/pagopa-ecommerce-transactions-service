package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.TransactionAuthorizationStatusUpdatedEvent;
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
public class AuthorizationUpdateProjectionHandler implements ProjectionHandler<TransactionAuthorizationStatusUpdatedEvent, Mono<TransactionActivated>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<TransactionActivated> handle(TransactionAuthorizationStatusUpdatedEvent data) {
        return transactionsViewRepository.findById(data.getTransactionId())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(data.getNoticeCodes().get(0).getPaymentToken())))
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(data.getData().getNewTransactionStatus());
                    return transactionsViewRepository.save(transactionDocument);
                })
                .map(transactionDocument -> new TransactionActivated(
                        new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                        transactionDocument.getNoticeCodes().stream().map(noticeCode ->
                                new NoticeCode(new PaymentToken(noticeCode.getPaymentToken()),
                                        new RptId(noticeCode.getRptId()),
                                        new TransactionAmount(noticeCode.getAmount()),
                                        new TransactionDescription(noticeCode.getDescription())
                                        )).toList(),
                        new Email(transactionDocument.getEmail()),
                        null,
                        null,
                        ZonedDateTime.parse(transactionDocument.getCreationDate()),
                        transactionDocument.getStatus()));
    }
}
