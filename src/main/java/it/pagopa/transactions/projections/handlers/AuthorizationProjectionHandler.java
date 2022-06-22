package it.pagopa.transactions.projections.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.data.AuthorizationData;
import it.pagopa.transactions.documents.Transaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AuthorizationProjectionHandler implements ProjectionHandler<AuthorizationData, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(AuthorizationData data) {
        return transactionsViewRepository.findByPaymentToken(data.transaction().getPaymentToken().value())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(data.transaction().getPaymentToken().value())))
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED);
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
