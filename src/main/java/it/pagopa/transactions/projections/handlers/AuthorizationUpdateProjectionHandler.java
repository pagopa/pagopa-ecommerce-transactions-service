package it.pagopa.transactions.projections.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.documents.Transaction;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AuthorizationUpdateProjectionHandler implements ProjectionHandler<UpdateAuthorizationStatusData, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(UpdateAuthorizationStatusData data) {
        return transactionsViewRepository.findByPaymentToken(data.transaction().getPaymentToken().value())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(data.transaction().getPaymentToken().value())))
                .flatMap(transactionDocument -> {
                    TransactionStatusDto newStatus;

                    switch (data.updateAuthorizationRequest().getAuthorizationResult()) {
                        case OK -> newStatus = TransactionStatusDto.AUTHORIZED;
                        case KO -> newStatus = TransactionStatusDto.AUTHORIZATION_FAILED;
                        default -> {
                            return Mono.error(new IllegalStateException("Unexpected AuthorizationResult value: " + data.updateAuthorizationRequest().getAuthorizationResult()));
                        }
                    }

                    transactionDocument.setStatus(newStatus);
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
