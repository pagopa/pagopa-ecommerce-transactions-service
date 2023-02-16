package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionUpdateAuthorizationHandler implements
        CommandHandler<TransactionUpdateAuthorizationCommand, Mono<TransactionAuthorizationCompletedEvent>> {

    @Autowired
    NodeForPspClient nodeForPspClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository;

    @Override
    public Mono<TransactionAuthorizationCompletedEvent> handle(TransactionUpdateAuthorizationCommand command) {
        TransactionActivated transaction = command.getData().transaction();

        if (transaction
                .getStatus() != it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_REQUESTED) {
            log.error("Error: requesting authorization update for transaction in state {}", transaction.getStatus());
            return Mono.error(new AlreadyProcessedException(command.getData().transaction().getTransactionId()));
        } else {
            UpdateAuthorizationRequestDto updateAuthorizationRequest = command.getData().updateAuthorizationRequest();
            return Mono.just(updateAuthorizationRequest.getAuthorizationResult().toString())
                    .map(AuthorizationResultDto::fromValue)
                    .flatMap(
                            authorizationResultDto -> Mono.just(
                                    new TransactionAuthorizationCompletedEvent(
                                            transaction.getTransactionId().value().toString(),
                                            new TransactionAuthorizationCompletedData(
                                                    updateAuthorizationRequest.getAuthorizationCode(),
                                                    authorizationResultDto
                                            )
                                    )
                            )
                    ).flatMap(transactionEventStoreRepository::save);
        }
    }
}
