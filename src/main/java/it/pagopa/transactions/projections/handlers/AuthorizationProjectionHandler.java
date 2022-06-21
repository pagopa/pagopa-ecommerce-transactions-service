package it.pagopa.transactions.projections.handlers;

import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.transactions.commands.data.AuthorizationData;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

public class AuthorizationProjectionHandler implements ProjectionHandler<AuthorizationData, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository viewEventStoreRepository;

    @Override
    public Mono<Transaction> handle(AuthorizationData data) {
        throw new RuntimeException();
    }
}
