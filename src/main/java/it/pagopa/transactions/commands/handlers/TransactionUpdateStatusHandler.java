package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateTransactionStatusRequestDto;
import it.pagopa.transactions.commands.TransactionUpdateStatusCommand;
import it.pagopa.transactions.commands.data.UpdateTransactionStatusData;
import it.pagopa.transactions.documents.TransactionStatusUpdateData;
import it.pagopa.transactions.documents.TransactionStatusUpdatedEvent;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionUpdateStatusHandler
        implements CommandHandler<TransactionUpdateStatusCommand, Mono<TransactionStatusUpdatedEvent>> {

    @Autowired
    private TransactionsEventStoreRepository<TransactionStatusUpdateData> transactionEventStoreRepository;

    @Override
    public Mono<TransactionStatusUpdatedEvent> handle(TransactionUpdateStatusCommand command) {

        return Mono.just(command)
                .filterWhen(commandData -> Mono
                        .just(commandData.getData().transaction().getStatus() == TransactionStatusDto.CLOSED || commandData.getData().transaction().getStatus() == TransactionStatusDto.INIT_REQUESTED))
                .switchIfEmpty(Mono.error(new AlreadyProcessedException(command.getRptId())))
                .flatMap(commandData -> {

                    TransactionStatusDto updatedStatus;

                    final UpdateTransactionStatusRequestDto updateTransactionRequest = command.getData().updateTransactionRequest();

                    if ( updateTransactionRequest.getAuthorizationResult() != null ) {
                        switch (updateTransactionRequest.getAuthorizationResult()) {
                            case OK -> updatedStatus = TransactionStatusDto.NOTIFIED;
                            case KO -> updatedStatus = TransactionStatusDto.NOTIFIED_FAILED;
                            default -> {
                                return Mono.error(new RuntimeException("Invalid result enum value"));
                            }
                        }
                    } else if ( updateTransactionRequest.getActivationResult() != null ) {
                        switch (updateTransactionRequest.getActivationResult()) {
                            case OK -> updatedStatus = TransactionStatusDto.INITIALIZED;
                            default -> {
                                return Mono.error(new RuntimeException("Invalid result enum value"));
                            }
                        }
                    } else {
                        return Mono.error(new RuntimeException("Invalid result enum value"));
                    }

                    TransactionStatusUpdateData statusUpdateData = new TransactionStatusUpdateData(
                            commandData.getData()
                                    .updateTransactionRequest().getAuthorizationResult(),
                            updatedStatus, commandData.getData().updateTransactionRequest().getActivationResult());

                    TransactionStatusUpdatedEvent event = new TransactionStatusUpdatedEvent(
                            commandData.getData().transaction().getTransactionId().value().toString(),
                            commandData.getData().transaction().getRptId().value(),
                            commandData.getData().transaction().getPaymentToken().value(),
                            statusUpdateData);

                    return transactionEventStoreRepository.save(event);
                });
    }
}
