package it.pagopa.transactions.projections.handlers;

import it.pagopa.transactions.server.model.TransactionStatusDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import it.pagopa.transactions.documents.Transaction;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionsProjectionHandler
		implements ProjectionHandler<NewTransactionResponseDto, Mono<Transaction>> {

	@Autowired
	private TransactionsViewRepository viewEventStoreRepository;

	@Override
	public Mono<Transaction> handle(NewTransactionResponseDto data) {

		Transaction transaction = new Transaction(data.getPaymentToken(),
				data.getRptId(), data.getReason(),
				data.getAmount(), TransactionStatusDto.INITIALIZED);

		return viewEventStoreRepository
				.save(transaction)
				.doOnNext(event -> log.info("Transactions update view for rptId: {}", event.getRptId()));
	}

}
