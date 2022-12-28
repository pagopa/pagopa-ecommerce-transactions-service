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
        List<PaymentNotice> PaymentNoticeList = transactionActivationRequestedEvent.getData().getPaymentNotices()
                .stream()
                .map(
                        PaymentNotice -> new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                new PaymentToken(null),
                                new RptId(PaymentNotice.getRptId()),
                                new TransactionAmount(PaymentNotice.getAmount()),
                                new TransactionDescription(PaymentNotice.getDescription()),
                                new PaymentContextCode(null)
                        )
                ).toList();
        Email email = new Email(transactionActivationRequestedEvent.getData().getEmail());

        TransactionActivationRequested transaction = new TransactionActivationRequested(
                transactionId,
                PaymentNoticeList,
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
                                        event.getPaymentNotices().stream()
                                                .map(it.pagopa.ecommerce.commons.documents.PaymentNotice::getRptId)
                                                .toList()
                                )
                        )
                )
                .thenReturn(transaction);
    }
}
