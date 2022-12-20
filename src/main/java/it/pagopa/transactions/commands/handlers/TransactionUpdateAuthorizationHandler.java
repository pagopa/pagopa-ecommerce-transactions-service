package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.NoticeCode;
import it.pagopa.ecommerce.commons.documents.TransactionAuthorizationStatusUpdateData;
import it.pagopa.ecommerce.commons.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.ecommerce.commons.domain.TransactionActivated;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Arrays;

@Component
@Slf4j
public class TransactionUpdateAuthorizationHandler implements
        CommandHandler<TransactionUpdateAuthorizationCommand, Mono<TransactionAuthorizationStatusUpdatedEvent>> {

    @Autowired
    NodeForPspClient nodeForPspClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationStatusUpdateData> transactionEventStoreRepository;

    @Override
    public Mono<TransactionAuthorizationStatusUpdatedEvent> handle(TransactionUpdateAuthorizationCommand command) {
        TransactionActivated transaction = command.getData().transaction();

        if (transaction
                .getStatus() != it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_REQUESTED) {
            log.error("Error: requesting authorization update for transaction in state {}", transaction.getStatus());
            return Mono.error(new AlreadyProcessedException(command.getRptId()));
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

            TransactionAuthorizationStatusUpdateData statusUpdateData = new TransactionAuthorizationStatusUpdateData(
                    AuthorizationResultDto.fromValue(updateAuthorizationRequest.getAuthorizationResult().toString()),
                    newStatus,
                    updateAuthorizationRequest.getAuthorizationCode()
            );

            TransactionAuthorizationStatusUpdatedEvent event = new TransactionAuthorizationStatusUpdatedEvent(
                    transaction.getTransactionId().value().toString(),
                    transaction.getTransactionActivatedData().getNoticeCodes(),
                    statusUpdateData
            );

            return transactionEventStoreRepository.save(event);
        }
    }
}
