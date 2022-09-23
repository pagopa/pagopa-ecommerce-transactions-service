package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.NodoPerPM;
import it.pagopa.transactions.commands.TransactionActivateResultCommand;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.documents.TransactionInitEvent;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.domain.TransactionInitialized;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.PaymentRequestInfo;
import it.pagopa.transactions.repositories.PaymentRequestsInfoRepository;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionSystemException;
import reactor.core.publisher.Mono;

import javax.validation.constraints.NotNull;
import java.lang.annotation.Annotation;
import java.util.UUID;

@Component
@Slf4j
public class TransactionActivateResultHandler
		implements CommandHandler<TransactionActivateResultCommand, Mono<TransactionInitEvent>> {

	@Autowired
	private TransactionsEventStoreRepository<TransactionInitData> transactionEventStoreRepository;

	@Autowired private NodoPerPM nodoPerPM;

	@Autowired private PaymentRequestsInfoRepository paymentRequestsInfoRepository;

	@Override
	public Mono<TransactionInitEvent> handle(TransactionActivateResultCommand command) {

		return Mono.just(command)
				.filterWhen(commandData -> Mono
						.just(commandData.getData().transactionInitialized().getStatus() == TransactionStatusDto.INIT_REQUESTED))
				.switchIfEmpty(Mono.error(new AlreadyProcessedException(command.getRptId())))
				.flatMap(commandData2 -> {

					final String paymentToken = commandData2.getData().activationResultData().getPaymentToken();
					final String rptId = commandData2.getRptId().value();
					final TransactionInitialized transactionInitialized = commandData2.getData().transactionInitialized();

					final String transactionId = commandData2.getData().transactionInitialized().getTransactionId().toString();
					TransactionInitData data = new TransactionInitData();
					data.setAmount(transactionInitialized.getAmount().value());
					data.setDescription(transactionInitialized.getDescription().value());

					//Edit this function
					return nodoPerPM.chiediInformazioniPagamento(paymentToken)
							.flatMap(informazioniPagamentoDto -> {
								log.info("chiediInformazioniPagamento info for rptID {} with paymentToken {} succeed", rptId, paymentToken);
								return paymentRequestsInfoRepository.findById(commandData2.getRptId())
										.map(Mono::just).orElseGet(Mono::empty)
										.switchIfEmpty(Mono.defer(() -> Mono.error(new TransactionNotFoundException("Transaction not found for rptID " + rptId + " with paymentToken "+ paymentToken))))
										.doOnSuccess(paymentRequestInfo -> {
											paymentRequestsInfoRepository.save(
													new PaymentRequestInfo(
															paymentRequestInfo.id(),
															paymentRequestInfo.paFiscalCode(),
															paymentRequestInfo.paName(),
															paymentRequestInfo.description(),
															paymentRequestInfo.amount(),
															paymentRequestInfo.dueDate(),
															paymentRequestInfo.isNM3(),
															paymentToken,
															paymentRequestInfo.idempotencyKey())
											);
										});
							})
							.doOnError(throwable -> {
								log.error("chiediInformazioniPagamento failed for paymentToken {}", paymentToken);
								throw new TransactionNotFoundException("chiediInformazioniPagamento failed for paymentToken " + paymentToken);
							})
							.flatMap((informazioniPagamentoDto) -> {
								TransactionInitEvent transactionInitializedEvent =
										new TransactionInitEvent(
												transactionId,
												rptId,
												paymentToken,
												data);

								return transactionEventStoreRepository.save(transactionInitializedEvent);
							});
				});
	}
}