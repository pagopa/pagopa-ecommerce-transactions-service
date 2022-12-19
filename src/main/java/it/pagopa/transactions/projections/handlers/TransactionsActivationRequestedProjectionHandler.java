package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.TransactionActivationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class TransactionsActivationRequestedProjectionHandler
        implements ProjectionHandler<TransactionActivationRequestedEvent, Mono<TransactionActivationRequested>> {

    @Autowired
    private TransactionsViewRepository viewEventStoreRepository;

    @Override
    public Mono<TransactionActivationRequested> handle(
                                                       TransactionActivationRequestedEvent transactionActivationRequestedEvent
    ) {

        TransactionId transactionId = new TransactionId(
                UUID.fromString(transactionActivationRequestedEvent.getTransactionId())
        );
        List<NoticeCode> noticeCodeList = transactionActivationRequestedEvent.getNoticeCodes().stream()
                .map(
                        noticeCode -> new NoticeCode(
                                null,
                                new RptId(noticeCode.getRptId()),
                                new TransactionAmount(noticeCode.getAmount()),
                                new TransactionDescription(noticeCode.getDescription())
                        )
                ).toList();
        Email email = new Email(transactionActivationRequestedEvent.getData().getEmail());

        TransactionActivationRequested transaction = new TransactionActivationRequested(
                transactionId,
                noticeCodeList,
                email,
                TransactionStatusDto.ACTIVATION_REQUESTED
        );

        it.pagopa.ecommerce.commons.documents.Transaction transactionDocument = it.pagopa.ecommerce.commons.documents.Transaction
                .from(transaction);

        return viewEventStoreRepository
                .save(transactionDocument)
                .doOnNext(
                        event -> log.info(
                                "Transactions update view for rptId: {}",
                                String.join(
                                        ",",
                                        event.getNoticeCodes().stream()
                                                .map(it.pagopa.ecommerce.commons.documents.NoticeCode::getRptId)
                                                .toList()
                                )
                        )
                )
                .thenReturn(transaction);
    }
}
