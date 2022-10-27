package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.domain.EmptyTransaction;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.domain.TransactionWithCompletedAuthorization;
import it.pagopa.transactions.domain.pojos.BaseTransaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.EuroUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class TransactionSendClosureHandler implements CommandHandler<TransactionClosureSendCommand, Mono<TransactionClosureSentEvent>> {

    @Autowired
    private TransactionsEventStoreRepository<TransactionClosureSendData> transactionEventStoreRepository;

    @Autowired
    private TransactionsEventStoreRepository<Object> eventStoreRepository;

    @Autowired
    NodeForPspClient nodeForPspClient;

    @Override
    public Mono<TransactionClosureSentEvent> handle(TransactionClosureSendCommand command) {
        Mono<Transaction> transaction = replayTransactionEvents(command.getData().transaction().getTransactionId().value());

        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(t -> log.error("Error: requesting closure for transaction in state {}", t.getStatus()))
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getRptId())));

        return transaction
                .cast(BaseTransaction.class)
                .filter(t -> t.getStatus() == TransactionStatusDto.AUTHORIZED)
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionWithCompletedAuthorization.class)
                .flatMap(tx -> {
                    UpdateAuthorizationRequestDto updateAuthorizationRequestDto = command.getData().updateAuthorizationRequest();
                    TransactionAuthorizationRequestData transactionAuthorizationRequestData = tx.getTransactionAuthorizationRequestData();
                    TransactionAuthorizationStatusUpdateData transactionAuthorizationStatusUpdateData = tx.getTransactionAuthorizationStatusUpdateData();

                    ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                            .paymentTokens(List.of(tx.getTransactionActivatedData().getPaymentToken()))
                            .outcome(authorizationResultToOutcomeV2(transactionAuthorizationStatusUpdateData.getAuthorizationResult()))
                            .idPSP(transactionAuthorizationRequestData.getPspId())
                            .idBrokerPSP(transactionAuthorizationRequestData.getBrokerName())
                            .idChannel(transactionAuthorizationRequestData.getPspChannelCode())
                            .transactionId(tx.getTransactionId().value().toString())
                            .totalAmount(EuroUtils.euroCentsToEuro(tx.getAmount().value() + transactionAuthorizationRequestData.getFee()))
                            .fee(EuroUtils.euroCentsToEuro(transactionAuthorizationRequestData.getFee()))
                            .timestampOperation(updateAuthorizationRequestDto.getTimestampOperation())
                            .paymentMethod(transactionAuthorizationRequestData.getPaymentTypeCode())
                            .additionalPaymentInformations(
                                    Map.of(
                                            "outcome_payment_gateway", transactionAuthorizationStatusUpdateData.getAuthorizationResult().toString(),
                                            "authorization_code", updateAuthorizationRequestDto.getAuthorizationCode()
                                    )
                            );

                    return nodeForPspClient.closePaymentV2(closePaymentRequest)
                            .flatMap(response -> {
                                TransactionStatusDto updatedStatus;

                                switch (response.getOutcome()) {
                                    case OK -> updatedStatus = TransactionStatusDto.CLOSED;
                                    case KO -> updatedStatus = TransactionStatusDto.CLOSURE_FAILED;
                                    default -> {
                                        return Mono.error(new RuntimeException("Invalid result enum value"));
                                    }
                                }

                                TransactionClosureSentEvent event = new TransactionClosureSentEvent(
                                        command.getData().transaction().getTransactionId().value().toString(),
                                        command.getData().transaction().getRptId().value(),
                                        command.getData().transaction().getTransactionActivatedData().getPaymentToken(),
                                        new TransactionClosureSendData(
                                                response.getOutcome(),
                                                updatedStatus,
                                                updateAuthorizationRequestDto.getAuthorizationCode()
                                        )
                                );

                                return transactionEventStoreRepository.save(event);

                            });
                });
    }

    private ClosePaymentRequestV2Dto.OutcomeEnum authorizationResultToOutcomeV2(AuthorizationResultDto authorizationResult) {
        switch (authorizationResult) {
            case OK -> {
                return ClosePaymentRequestV2Dto.OutcomeEnum.OK;
            }
            case KO -> {
                return ClosePaymentRequestV2Dto.OutcomeEnum.KO;
            }
            default ->
                    throw new IllegalArgumentException("Missing authorization result enum value mapping to Nodo closePaymentV2 outcome");
        }
    }

    private Mono<Transaction> replayTransactionEvents(UUID transactionId) {
        Flux<TransactionEvent<Object>> events = eventStoreRepository.findByTransactionId(transactionId.toString());

        return events.reduce(new EmptyTransaction(), Transaction::applyEvent);
    }
}
