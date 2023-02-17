package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.domain.v1.Transaction;
import it.pagopa.ecommerce.commons.domain.v1.TransactionWithRequestedAuthorization;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
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
import reactor.util.function.Tuples;

@Component
@Slf4j
public class TransactionUpdateAuthorizationHandler extends
        BaseHandler<TransactionUpdateAuthorizationCommand, Mono<TransactionAuthorizationCompletedEvent>> {

    private final NodeForPspClient nodeForPspClient;

    private final TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository;

    private final TransactionsEventStoreRepository<Object> eventStoreRepository;

    @Autowired
    protected TransactionUpdateAuthorizationHandler(
            TransactionsEventStoreRepository<Object> eventStoreRepository,
            NodeForPspClient nodeForPspClient,
            TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository,
            TransactionsEventStoreRepository<Object> eventStoreRepository1
    ) {
        super(eventStoreRepository);
        this.nodeForPspClient = nodeForPspClient;
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.eventStoreRepository = eventStoreRepository1;
    }

    @Override
    public Mono<TransactionAuthorizationCompletedEvent> handle(TransactionUpdateAuthorizationCommand command) {
        Mono<Transaction> transaction = replayTransactionEvents(
                command.getData().transaction().getTransactionId().value()
        );
        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(
                        t -> log.error(
                                "Error: requesting authorization update for transaction in state {}",
                                t.getStatus()
                        )
                )
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));
        UpdateAuthorizationRequestDto updateAuthorizationRequest = command.getData().updateAuthorizationRequest();
        return transaction
                .cast(BaseTransaction.class)
                .filter(
                        t -> t.getStatus() == TransactionStatusDto.AUTHORIZATION_REQUESTED
                )
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionWithRequestedAuthorization.class)
                .map(
                        transactionWithRequestedAuthorization -> Tuples.of(
                                transactionWithRequestedAuthorization,
                                AuthorizationResultDto
                                        .fromValue(updateAuthorizationRequest.getAuthorizationResult().toString())
                        )
                )
                .flatMap(args -> {
                    TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = args.getT1();
                    AuthorizationResultDto authorizationResultDto = args.getT2();
                    return Mono.just(
                            new TransactionAuthorizationCompletedEvent(
                                    transactionWithRequestedAuthorization.getTransactionId().value().toString(),
                                    new TransactionAuthorizationCompletedData(
                                            updateAuthorizationRequest.getAuthorizationCode(),
                                            authorizationResultDto
                                    )
                            )
                    );
                }
                ).flatMap(transactionEventStoreRepository::save);

    }

}
