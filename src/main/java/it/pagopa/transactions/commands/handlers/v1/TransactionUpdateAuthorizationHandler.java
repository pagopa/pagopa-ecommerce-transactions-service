package it.pagopa.transactions.commands.handlers.v1;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.generated.events.v1.TransactionStatus;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.handlers.TransactionUpdateAuthorizationHandlerCommon;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component(TransactionUpdateAuthorizationHandler.QUALIFIER_NAME)
@Slf4j
public class TransactionUpdateAuthorizationHandler extends TransactionUpdateAuthorizationHandlerCommon {

    public static final String QUALIFIER_NAME = "TransactionUpdateAuthorizationHandlerV1";
    private final TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData> transactionEventStoreRepository;

    @Autowired
    protected TransactionUpdateAuthorizationHandler(
            TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository,
            AuthRequestDataUtils extractAuthRequestData,
            TransactionsUtils transactionsUtils
    ) {
        super(extractAuthRequestData, transactionsUtils);
        this.transactionEventStoreRepository = transactionEventStoreRepository;
    }

    @Override
    public Mono<BaseTransactionEvent<?>> handle(TransactionUpdateAuthorizationCommand command) {
        TransactionId transactionId = command.getData().transactionId();
        Mono<BaseTransactionEvent<?>> alreadyProcessedError = Mono.error(new AlreadyProcessedException(transactionId));
        UpdateAuthorizationRequestDto updateAuthorizationRequest = command.getData().updateAuthorizationRequest();
        AuthRequestDataUtils.AuthRequestData authRequestDataExtracted = extractAuthRequestData
                .from(updateAuthorizationRequest, transactionId);
        TransactionStatus transactionStatus = TransactionStatus.valueOf(command.getData().transactionStatus());

        if (transactionStatus.equals(TransactionStatus.AUTHORIZATION_REQUESTED)) {
            return Mono.just(
                    AuthorizationResultDto
                            .fromValue(
                                    authRequestDataExtracted.outcome()
                            )
            )
                    .flatMap(
                            authorizationResultDto -> Mono.just(
                                    new it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent(
                                            transactionId.value(),
                                            new it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData(
                                                    authRequestDataExtracted.authorizationCode(),
                                                    authRequestDataExtracted.rrn(),
                                                    updateAuthorizationRequest.getTimestampOperation().toString(),
                                                    authRequestDataExtracted.errorCode(),
                                                    authorizationResultDto
                                            )
                                    )
                            )
                    )
                    .flatMap(transactionEventStoreRepository::save);
        } else {
            return alreadyProcessedError;
        }
    }
}
