package it.pagopa.transactions.projections.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionInitEvent;
import it.pagopa.transactions.domain.*;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import it.pagopa.transactions.repositories.TransactionsViewRepository;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionsProjectionHandler
    implements ProjectionHandler<TransactionInitEvent, Mono<Transaction>> {

  @Autowired private TransactionsViewRepository viewEventStoreRepository;

  @Override
  public Mono<Transaction> handle(TransactionInitEvent initEvent) {

    TransactionId transactionId = new TransactionId(UUID.fromString(initEvent.getTransactionId()));
    PaymentToken paymentToken = new PaymentToken(initEvent.getPaymentToken());
    RptId rptId = new RptId(initEvent.getRptId());
    TransactionDescription description = new TransactionDescription(initEvent.getData().getDescription());
    TransactionAmount amount = new TransactionAmount(initEvent.getData().getAmount());
    Email email = new Email(initEvent.getData().getEmail());
    final TransactionStatusDto transactionStatus =
            Optional.ofNullable(paymentToken.value()).isEmpty()
                    ? TransactionStatusDto.INIT_REQUESTED
                    : TransactionStatusDto.INITIALIZED;

    TransactionInitialized transaction =
        new TransactionInitialized(transactionId, paymentToken, rptId, description, amount, email, transactionStatus);

    it.pagopa.transactions.documents.Transaction transactionDocument =
        it.pagopa.transactions.documents.Transaction.from(transaction);

    return viewEventStoreRepository
        .save(transactionDocument)
        .doOnNext(event -> log.info("Transactions update view for rptId: {}", event.getRptId()))
        .thenReturn(transaction);
  }
}
