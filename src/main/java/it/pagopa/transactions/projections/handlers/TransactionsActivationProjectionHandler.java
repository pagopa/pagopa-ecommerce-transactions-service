package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
public class TransactionsActivationProjectionHandler
        implements ProjectionHandler<TransactionActivatedEvent, Mono<TransactionActivated>> {

    @Autowired
    private TransactionsViewRepository viewEventStoreRepository;

    @Override
    public Mono<TransactionActivated> handle(TransactionActivatedEvent event) {
        TransactionActivatedData data = event.getData();
        TransactionId transactionId = new TransactionId(UUID.fromString(event.getTransactionId()));
        List<NoticeCode> noticeCodeList = data.getNoticeCodes().stream().map(
                noticeCodeData -> new NoticeCode(
                        new PaymentToken(noticeCodeData.getPaymentToken()),
                        new RptId(noticeCodeData.getRptId()),
                        new TransactionAmount(noticeCodeData.getAmount()),
                        new TransactionDescription(noticeCodeData.getDescription()),
                        new PaymentContextCode(noticeCodeData.getPaymentContextCode())
                )
        ).toList();
        Email email = new Email(event.getData().getEmail());
        String faultCode = event.getData().getFaultCode();
        String faultCodeString = event.getData().getFaultCodeString();

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                noticeCodeList,
                email,
                faultCode,
                faultCodeString,
                TransactionStatusDto.ACTIVATED
        );

        it.pagopa.ecommerce.commons.documents.Transaction transactionDocument = it.pagopa.ecommerce.commons.documents.Transaction
                .from(transaction);

        return viewEventStoreRepository
                .save(transactionDocument)
                .doOnNext(t -> log.info("Transactions update view for transactionId: {}", t.getTransactionId()))
                .thenReturn(transaction);
    }
}
