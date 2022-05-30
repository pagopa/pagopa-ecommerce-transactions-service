package it.pagopa.transactions.projections.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.domain.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import it.pagopa.transactions.repositories.TransactionsViewRepository;

import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@Slf4j
public class TransactionsProjectionHandler
		implements ProjectionHandler<NewTransactionResponseDto, Mono<Transaction>> {

	@Autowired
	private TransactionsViewRepository viewEventStoreRepository;

	@Override
	public Mono<Transaction> handle(NewTransactionResponseDto data) {

		PaymentToken paymentToken = new PaymentToken(data.getPaymentToken());
		RptId rptId = new RptId(data.getRptId());
		TransactionDescription description = new TransactionDescription(data.getReason());
		TransactionAmount amount = new TransactionAmount(data.getAmount());

		Transaction transaction = new Transaction(
				new TransactionId(UUID.randomUUID().toString()),
				paymentToken,
				rptId,
				description,
				amount,
				TransactionStatusDto.INITIALIZED
		);

		it.pagopa.transactions.documents.Transaction transactionDocument = it.pagopa.transactions.documents.Transaction.from(transaction);

		return viewEventStoreRepository
				.save(transactionDocument)
				.doOnNext(event -> log.info("Transactions update view for rptId: {}", event.getRptId()))
				.thenReturn(transaction);
	}

}
