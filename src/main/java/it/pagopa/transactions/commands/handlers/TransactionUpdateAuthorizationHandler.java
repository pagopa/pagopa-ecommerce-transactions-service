package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.domain.v1.TransactionWithRequestedAuthorization;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
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
    private final AuthRequestDataUtils extractAuthRequestData;

    @Autowired
    protected TransactionUpdateAuthorizationHandler(
            TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository,
            AuthRequestDataUtils extractAuthRequestData
    ) {
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.extractAuthRequestData = extractAuthRequestData;
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
        AuthRequestDataUtils.AuthRequestData authRequestDataExtracted = extractAuthRequestData
                .from(command.getData().updateAuthorizationRequest());
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
                                                authRequestDataExtracted.outcome()
                                        )
                        )
                )
                .flatMap(args -> {
                    TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = args.getT1();
                    AuthorizationResultDto authorizationResultDto = args.getT2();
                    return Mono.just(
                            new TransactionAuthorizationCompletedEvent(
                                    transactionWithRequestedAuthorization.getTransactionId().value(),
                                    new TransactionAuthorizationCompletedData(
                                            authRequestDataExtracted.authorizationCode(),
                                            authRequestDataExtracted.rrn(),
                                            authorizationResultDto
                                    )
                            )
                    );
                }
                ).flatMap(transactionEventStoreRepository::save);

    }

}
