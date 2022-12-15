package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.*;
import it.pagopa.ecommerce.commons.domain.EmptyTransaction;
import it.pagopa.ecommerce.commons.domain.Transaction;
import it.pagopa.ecommerce.commons.domain.TransactionWithCompletedAuthorization;
import it.pagopa.ecommerce.commons.domain.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
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
import java.util.stream.Collectors;

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
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getNoticeCodes().get(0).rptId())));

        return transaction
                .cast(BaseTransaction.class)
                .filter(t -> t.getStatus() == it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZED)
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionWithCompletedAuthorization.class)
                .flatMap(tx -> {
                    UpdateAuthorizationRequestDto updateAuthorizationRequestDto = command.getData().updateAuthorizationRequest();
                    TransactionAuthorizationRequestData transactionAuthorizationRequestData = tx.getTransactionAuthorizationRequestData();
                    TransactionAuthorizationStatusUpdateData transactionAuthorizationStatusUpdateData = tx.getTransactionAuthorizationStatusUpdateData();

                    ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                            .paymentTokens(tx.getTransactionActivatedData().getNoticeCodes().stream().map(noticeCode -> noticeCode.getPaymentToken()).toList())
                            .outcome(authorizationResultToOutcomeV2(transactionAuthorizationStatusUpdateData.getAuthorizationResult()))
                            .idPSP(transactionAuthorizationRequestData.getPspId())
                            .idBrokerPSP(transactionAuthorizationRequestData.getBrokerName())
                            .idChannel(transactionAuthorizationRequestData.getPspChannelCode())
                            .transactionId(tx.getTransactionId().value().toString())
                            .totalAmount(EuroUtils.euroCentsToEuro(tx.getNoticeCodes().stream().mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum() + transactionAuthorizationRequestData.getFee()))
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
                                        command.getData().transaction().getNoticeCodes().stream().map(
                                                noticeCode ->  new NoticeCode(
                                                        command.getData().transaction().getTransactionActivatedData().getNoticeCodes().
                                                                stream().filter(noticeCode1 -> noticeCode1.getRptId().equals(noticeCode.rptId().value())).findFirst().get()
                                                                .getPaymentToken(),
                                                        noticeCode.rptId().value(),
                                                        noticeCode.transactionDescription().value(),
                                                        noticeCode.transactionAmount().value()
                                                )).toList(),
                                        new TransactionClosureSendData(
                                                response.getOutcome(),
                                                updatedStatus
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
