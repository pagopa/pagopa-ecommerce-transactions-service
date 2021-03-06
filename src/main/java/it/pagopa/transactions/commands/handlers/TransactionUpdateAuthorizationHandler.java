package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdateData;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionUpdateAuthorizationHandler implements CommandHandler<TransactionUpdateAuthorizationCommand, Mono<TransactionAuthorizationStatusUpdatedEvent>> {

    @Autowired
    NodeForPspClient nodeForPspClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationStatusUpdateData> transactionEventStoreRepository;

    @Override
    public Mono<TransactionAuthorizationStatusUpdatedEvent> handle(TransactionUpdateAuthorizationCommand command) {
        Transaction transaction = command.getData().transaction();

        if (transaction.getStatus() != TransactionStatusDto.AUTHORIZATION_REQUESTED) {
            log.error("Error: requesting authorization update for transaction in state {}", transaction.getStatus());
            return Mono.error(new AlreadyProcessedException(transaction.getRptId()));
        } else {
            UpdateAuthorizationRequestDto updateAuthorizationRequest = command.getData().updateAuthorizationRequest();

            TransactionStatusDto newStatus;

            switch (updateAuthorizationRequest.getAuthorizationResult()) {
                case OK -> newStatus = TransactionStatusDto.AUTHORIZED;
                case KO -> newStatus = TransactionStatusDto.AUTHORIZATION_FAILED;
                default -> {
                    return Mono.error(new RuntimeException("Invalid authorization result enum value"));
                }
            }

            TransactionAuthorizationStatusUpdateData statusUpdateData =
                    new TransactionAuthorizationStatusUpdateData(
                            updateAuthorizationRequest.getAuthorizationResult(),
                            newStatus
                    );

            TransactionAuthorizationStatusUpdatedEvent event = new TransactionAuthorizationStatusUpdatedEvent(
                    transaction.getTransactionId().value().toString(),
                    transaction.getRptId().value(),
                    transaction.getPaymentToken().value(),
                    statusUpdateData
            );

            return transactionEventStoreRepository.save(event);
        }
    }
}
