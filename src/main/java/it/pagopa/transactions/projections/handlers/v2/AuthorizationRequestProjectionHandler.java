package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component(AuthorizationRequestProjectionHandler.QUALIFIER_NAME)
@Qualifier(AuthorizationRequestProjectionHandler.QUALIFIER_NAME)
@Slf4j
public class AuthorizationRequestProjectionHandler
        implements
        ProjectionHandler<AuthorizationRequestData, Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction>> {

    public static final String QUALIFIER_NAME = "authorizationRequestProjectionHandlerV2";
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(AuthorizationRequestData data) {
        return transactionsViewRepository.findById(data.transactionId().value())
                .cast(Transaction.class)
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
                    transactionDocument.setPaymentTypeCode(data.paymentTypeCode());
                    transactionDocument.setPspId(data.pspId());
                    transactionDocument.setFeeTotal(data.fee());
                    return transactionsViewRepository.save(transactionDocument);
                });
    }

    public Mono<Transaction> handle(
                                    AuthorizationRequestData data,
                                    String creationDate
    ) {
        return transactionsViewRepository.findById(data.transactionId().value())
                .cast(Transaction.class)
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
                    transactionDocument.setPaymentTypeCode(data.paymentTypeCode());
                    transactionDocument.setPspId(data.pspId());
                    transactionDocument.setFeeTotal(data.fee());
                    transactionDocument.setLastProcessedEventAt(
                            ZonedDateTime.parse(creationDate).toInstant().toEpochMilli()
                    );
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
