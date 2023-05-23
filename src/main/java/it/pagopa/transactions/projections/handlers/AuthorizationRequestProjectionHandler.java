package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AuthorizationRequestProjectionHandler
        implements ProjectionHandler<AuthorizationRequestData, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(AuthorizationRequestData data) {
        return transactionsViewRepository.findById(data.transaction().getTransactionId().value())
                .switchIfEmpty(
                        Mono.error(
                                new TransactionNotFoundException(
                                        data.transaction().getTransactionId().value()
                                )
                        )
                )
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED);
                    transactionDocument.setPaymentGateway(data.paymentGatewayId());

                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
