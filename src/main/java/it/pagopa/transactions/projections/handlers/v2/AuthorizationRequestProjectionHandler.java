package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.data.AuthorizationRequestedEventData;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;

@Component(AuthorizationRequestProjectionHandler.QUALIFIER_NAME)
@Qualifier(AuthorizationRequestProjectionHandler.QUALIFIER_NAME)
@Slf4j
public class AuthorizationRequestProjectionHandler
        implements
        ProjectionHandler<AuthorizationRequestedEventData, Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction>> {

    public static final String QUALIFIER_NAME = "authorizationRequestProjectionHandlerV2";

    private final TransactionsViewRepository transactionsViewRepository;
    private final boolean transactionsviewUpdateEnabled;

    @Autowired
    public AuthorizationRequestProjectionHandler(
            TransactionsViewRepository transactionsViewRepository,
            @Value("${transactionsview.update.enabled}") boolean transactionsviewUpdateEnabled
    ) {
        this.transactionsViewRepository = transactionsViewRepository;
        this.transactionsviewUpdateEnabled = transactionsviewUpdateEnabled;
    }

    @Override
    public Mono<Transaction> handle(AuthorizationRequestedEventData authorizationRequestedEventData) {
        AuthorizationRequestData data = authorizationRequestedEventData.authorizationRequestData();
        String creationDate = authorizationRequestedEventData.event().getCreationDate();
        return Mono.just(transactionsviewUpdateEnabled)
                .filter(t -> t)
                .flatMap(
                        t -> transactionsViewRepository.findById(data.transactionId().value())
                                .cast(Transaction.class)
                                .switchIfEmpty(
                                        Mono.error(
                                                new TransactionNotFoundException(
                                                        data.transactionId().value()
                                                )
                                        )
                                )
                                .flatMap(
                                        transactionDocument -> conditionallySaveTransactionView(
                                                transactionDocument,
                                                data,
                                                creationDate
                                        )
                                )
                );
    }

    private Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction> conditionallySaveTransactionView(
                                                                                                        it.pagopa.ecommerce.commons.documents.v2.Transaction transactionDocument,
                                                                                                        AuthorizationRequestData data,
                                                                                                        String creationDate
    ) {
        transactionDocument.setStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED);
        transactionDocument.setPaymentGateway(data.paymentGatewayId());
        transactionDocument.setPaymentTypeCode(data.paymentTypeCode());
        transactionDocument.setPspId(data.pspId());
        transactionDocument.setFeeTotal(data.fee());
        transactionDocument.setLastProcessedEventAt(
                ZonedDateTime.parse(creationDate).toInstant().toEpochMilli()
        );

        if (transactionsviewUpdateEnabled) {
            return transactionsViewRepository.save(transactionDocument);
        } else {
            return Mono.just(transactionDocument);
        }
    }
}
