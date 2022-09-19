package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.TransactionActivateResultCommand;
import it.pagopa.transactions.commands.data.ActivationResultData;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.documents.TransactionInitEvent;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
public class TransactionActivateResultHandler
		implements CommandHandler<TransactionActivateResultCommand, Mono<TransactionInitEvent>> {

	@Autowired
	private TransactionsEventStoreRepository<ActivationResultData> transactionEventStoreRepository;

	@Override
	public Mono<TransactionInitEvent> handle(TransactionActivateResultCommand command) {

		return Mono.just(command)
				.filterWhen(commandData -> Mono
						.just(commandData.getData().transaction().getStatus() == TransactionStatusDto.INIT_REQUESTED))
				.switchIfEmpty(Mono.error(new AlreadyProcessedException(command.getRptId())))
				.flatMap(commandData -> {

					final String paymentToken = command.getData().activationResultData().getPaymentToken();
					final String rptId = command.getRptId().value();
					final Transaction transaction = command.getData().transaction();

					final String transactionId = UUID.randomUUID().toString();
					TransactionInitData data = new TransactionInitData();
					data.setAmount(transaction.getAmount().value());
					data.setDescription(transaction.getDescription().value());

					/**
					 * TODO:
					 * 1.  nodoChioediInformazioni
					 * 2. transactionId <-> paymentToken
					 * */

					TransactionEvent<TransactionInitData> event =
							new TransactionInitEvent(
									transactionId,
									rptId,
									paymentToken,
									data);

					return transactionEventStoreRepository.save(event);
				});
	}
}