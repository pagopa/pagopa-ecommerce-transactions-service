package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.PgsTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.TransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.generated.events.v1.TransactionStatus;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.*;
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

import java.util.Objects;

@Component(TransactionUpdateAuthorizationHandler.QUALIFIER_NAME)
@Slf4j
public class TransactionUpdateAuthorizationHandler extends TransactionUpdateAuthorizationHandlerCommon {

    public static final String QUALIFIER_NAME = "TransactionUpdateAuthorizationHandlerV2";
    private final TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData> transactionEventStoreRepository;


    @Autowired
    protected TransactionUpdateAuthorizationHandler(
            TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository,
            AuthRequestDataUtils extractAuthRequestData,
            TransactionsUtils transactionsUtils) {
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
            UpdateAuthorizationRequestOutcomeGatewayDto outcomeGateway = command.getData().updateAuthorizationRequest().getOutcomeGateway();

            TransactionGatewayAuthorizationData authorizationData;
            if (Objects.requireNonNull(outcomeGateway) instanceof OutcomeNpgGatewayDto) {
                OutcomeNpgGatewayDto outcomeNpgGateway = ((OutcomeNpgGatewayDto) outcomeGateway);
                authorizationData = new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.valueOf(outcomeNpgGateway.getOperationResult().toString()),
                        outcomeNpgGateway.getOperationId(),
                        outcomeNpgGateway.getPaymentEndToEndId()
                );
            } else if (outcomeGateway instanceof OutcomeXpayGatewayDto || outcomeGateway instanceof OutcomeVposGatewayDto) {
                authorizationData = new PgsTransactionGatewayAuthorizationData(
                        authRequestDataExtracted.errorCode(),
                        AuthorizationResultDto
                                .fromValue(
                                        authRequestDataExtracted.outcome()
                                )
                );
            } else {
                throw new IllegalStateException("Unexpected value: " + outcomeGateway);
            }

            return Mono.just(
                            new it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedEvent(
                                    transactionId.value(),
                                    new it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData(
                                            authRequestDataExtracted.authorizationCode(),
                                            authRequestDataExtracted.rrn(),
                                            updateAuthorizationRequest.getTimestampOperation().toString(),
                                            authorizationData
                                    )))
                    .flatMap(transactionEventStoreRepository::save);
        } else {
            return alreadyProcessedError;
        }
    }
}
