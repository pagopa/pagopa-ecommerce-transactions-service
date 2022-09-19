package it.pagopa.transactions.projections.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.documents.TransactionInitEvent;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@Slf4j
public class TransactionsActivationProjectionHandler
		implements ProjectionHandler<TransactionInitEvent, Mono<Transaction>> {

	@Autowired
	private TransactionsViewRepository viewEventStoreRepository;

	@Override
	public Mono<Transaction> handle(TransactionInitEvent event) {

		TransactionInitData data = event.getData();
		TransactionId transactionId = new TransactionId(UUID.fromString(event.getTransactionId()));
		PaymentToken paymentToken = new PaymentToken(event.getPaymentToken());
		RptId rptId = new RptId(event.getRptId());
		TransactionDescription description = new TransactionDescription(data.getDescription());
		TransactionAmount amount = new TransactionAmount(data.getAmount());

		Transaction transaction =
				new Transaction(transactionId, paymentToken, rptId, description, amount, TransactionStatusDto.INITIALIZED);

		it.pagopa.transactions.documents.Transaction transactionDocument =
				it.pagopa.transactions.documents.Transaction.from(transaction);

		return viewEventStoreRepository
				.save(transactionDocument)
				.doOnNext(e -> log.info("Transactions update view for rptId: {}", e.getRptId()))
				.thenReturn(transaction);
	}
}
