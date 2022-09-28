package it.pagopa.transactions.projections.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionActivationRequestedEvent;
import it.pagopa.transactions.domain.*;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import it.pagopa.transactions.repositories.TransactionsViewRepository;

import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionsActivationRequestedProjectionHandler
    implements ProjectionHandler<TransactionActivationRequestedEvent, Mono<TransactionActivateRequested>> {

  @Autowired private TransactionsViewRepository viewEventStoreRepository;

  @Override
  public Mono<TransactionActivateRequested> handle(TransactionActivationRequestedEvent transactionActivationRequestedEvent) {

    TransactionId transactionId = new TransactionId(UUID.fromString(transactionActivationRequestedEvent.getTransactionId()));
    PaymentToken paymentToken = new PaymentToken(transactionActivationRequestedEvent.getPaymentToken());
    RptId rptId = new RptId(transactionActivationRequestedEvent.getRptId());
    TransactionDescription description = new TransactionDescription(transactionActivationRequestedEvent.getData().getDescription());
    TransactionAmount amount = new TransactionAmount(transactionActivationRequestedEvent.getData().getAmount());

    TransactionActivateRequested transaction =
        new TransactionActivateRequested(transactionId, paymentToken, rptId, description, amount, TransactionStatusDto.ACTIVATION_REQUESTED);

    it.pagopa.transactions.documents.Transaction transactionDocument =
        it.pagopa.transactions.documents.Transaction.from(transaction);

    return viewEventStoreRepository
        .save(transactionDocument)
        .doOnNext(event -> log.info("Transactions update view for rptId: {}", event.getRptId()))
        .thenReturn(transaction);
  }
}
