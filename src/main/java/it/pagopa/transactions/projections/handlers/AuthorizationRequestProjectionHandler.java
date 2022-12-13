package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.Transaction;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AuthorizationRequestProjectionHandler implements ProjectionHandler<AuthorizationRequestData, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(AuthorizationRequestData data) {
        return transactionsViewRepository.findById(data.transaction().getTransactionId().value().toString())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(data.transaction().getTransactionActivatedData().getPaymentToken())))
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED);
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
