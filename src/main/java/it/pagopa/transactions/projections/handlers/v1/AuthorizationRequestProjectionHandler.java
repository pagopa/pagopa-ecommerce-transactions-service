package it.pagopa.transactions.projections.handlers.v1;

import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component(AuthorizationRequestProjectionHandler.QUALIFIER_NAME)
@Slf4j
public class AuthorizationRequestProjectionHandler
        implements ProjectionHandler<AuthorizationRequestData, Mono<Transaction>> {

    public static final String QUALIFIER_NAME = "AuthorizationRequestProjectionHandlerV1";
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(AuthorizationRequestData data) {
        return transactionsViewRepository.findById(data.transactionId().value())
                .cast(it.pagopa.ecommerce.commons.documents.v1.Transaction.class)
                .switchIfEmpty(
                        Mono.error(
                                new TransactionNotFoundException(
                                        data.transactionId().value()
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
