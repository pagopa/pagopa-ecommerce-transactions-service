package it.pagopa.transactions.projections.handlers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import it.pagopa.transactions.documents.Transaction;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.utils.TransactionStatus;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class TransactionsProjectionHandler
		implements ProjectionHandler<NewTransactionResponseDto, Void> {

	@Autowired
	private TransactionsViewRepository viewEventStoreRepository;

	@Override
	public Void handle(NewTransactionResponseDto data) {

		Transaction transaction = new Transaction(data.getPaymentToken(),
				data.getRptId(), data.getReason(),
				data.getAmount(), TransactionStatus.TRANSACTION_INITIALIZED);

		viewEventStoreRepository.save(transaction)
				.doOnSuccess(event -> log.info("Transactions updata view for rptId: {}",
						event.getRptId()))
				.subscribe();
		return null;
	}

}
