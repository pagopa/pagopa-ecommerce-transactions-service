package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.domain.v1.TransactionWithRequestedAuthorization;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
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
public class TransactionUpdateAuthorizationHandler
        implements CommandHandler<TransactionUpdateAuthorizationCommand, Mono<TransactionAuthorizationCompletedEvent>> {

    private final TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository;

    @Autowired
    protected TransactionUpdateAuthorizationHandler(
            TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository
    ) {
        this.transactionEventStoreRepository = transactionEventStoreRepository;
    }

    @Override
    public Mono<TransactionAuthorizationCompletedEvent> handle(TransactionUpdateAuthorizationCommand command) {

        Mono<BaseTransaction> transaction = Mono.just(command.getData().transaction());
        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .doOnNext(
                        t -> log.error(
                                "Error: requesting authorization update for transaction in state {}",
                                t.getStatus()
                        )
                )
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));
        UpdateAuthorizationRequestDto updateAuthorizationRequest = command.getData().updateAuthorizationRequest();
        String outcome;
        String authorizationCode;
        String rrn;
        switch (updateAuthorizationRequest.getOutcomeGateway()){
            case OutcomeVposGatewayDto t -> {
                outcome = t.getOutcome().toString();
                authorizationCode = t.getAuthorizationCode();
                rrn = t.getRrn();
            }
            case OutcomeXpayGatewayDto t -> {
                outcome = t.getOutcome().toString();
                authorizationCode = t.getAuthorizationCode();
                rrn = null;
            }
            default ->
                    throw new IllegalStateException("Unexpected value: " + updateAuthorizationRequest.getOutcomeGateway());
        }
        return transaction
                .filter(
                        t -> t.getStatus() == TransactionStatusDto.AUTHORIZATION_REQUESTED
                )
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionWithRequestedAuthorization.class)
                .map(
                        transactionWithRequestedAuthorization -> Tuples.of(
                                transactionWithRequestedAuthorization,
                                AuthorizationResultDto
                                        .fromValue(
                                                outcome
                                        )
                        )
                )
                .flatMap(args -> {
                    TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = args.getT1();
                    AuthorizationResultDto authorizationResultDto = args.getT2();
                    return Mono.just(
                            new TransactionAuthorizationCompletedEvent(
                                    transactionWithRequestedAuthorization.getTransactionId().value().toString(),
                                    new TransactionAuthorizationCompletedData(
                                            authorizationCode,
                                            rrn,
                                            authorizationResultDto
                                    )
                            )
                    );
                }
                ).flatMap(transactionEventStoreRepository::save);

    }

}
