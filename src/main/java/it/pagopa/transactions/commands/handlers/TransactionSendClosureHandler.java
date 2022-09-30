package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentRequestDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestData;
import it.pagopa.transactions.documents.TransactionClosureSendData;
import it.pagopa.transactions.documents.TransactionClosureSentEvent;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.domain.EmptyTransaction;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.domain.TransactionActivated;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionEventCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class TransactionSendClosureHandler implements CommandHandler<TransactionClosureSendCommand, Mono<TransactionClosureSentEvent>> {

    @Autowired
    NodeForPspClient nodeForPspClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionClosureSendData> transactionEventStoreRepository;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestData> authorizationRequestedEventStoreRepository;

    @Autowired
    private TransactionsEventStoreRepository<Object> eventStoreRepository;

    @Override
    public Mono<TransactionClosureSentEvent> handle(TransactionClosureSendCommand command) {
        TransactionActivated transaction = command.getData().transaction();

        if (transaction.getStatus() != TransactionStatusDto.AUTHORIZED) {
            log.error("Error: requesting closure status update for transaction in state {}", transaction.getStatus());
            return Mono.error(new AlreadyProcessedException(transaction.getRptId()));
        } else {
            UpdateAuthorizationRequestDto updateAuthorizationRequest = command.getData().updateAuthorizationRequest();
            return authorizationRequestedEventStoreRepository.findByTransactionIdAndEventCode(
                            transaction.getTransactionId().value().toString(),
                            TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT
                    )
                    .switchIfEmpty(Mono.error(new TransactionNotFoundException(transaction.getTransactionActivatedData().getPaymentToken())))
                    .flatMap(authorizationRequestedEvent -> {
                        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

                        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                                .paymentTokens(List.of(transaction.getTransactionActivatedData().getPaymentToken()))
                                .outcome(authorizationResultToOutcomeV2(updateAuthorizationRequest.getAuthorizationResult()))
                                .idPSP(authorizationRequestData.getPspId())
                                .idBrokerPSP(authorizationRequestData.getBrokerName())
                                .idChannel(authorizationRequestData.getPspChannelCode())
                                .transactionId(transaction.getTransactionId().value().toString())
                                .totalAmount(new BigDecimal(transaction.getAmount().value() + authorizationRequestData.getFee()))
                                .fee(new BigDecimal(authorizationRequestData.getFee()))
                                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                                .paymentMethod("") // FIXME
                                .additionalPaymentInformations(
                                        Map.of(
                                                "outcome_payment_gateway", updateAuthorizationRequest.getAuthorizationResult().toString(),
                                                "authorization_code", updateAuthorizationRequest.getAuthorizationCode()
                                        )
                                );

                        return nodeForPspClient.closePaymentV2(closePaymentRequest);
                    })
                    .flatMap(response -> {
                        TransactionStatusDto newStatus;

                        switch (response.getOutcome()) {
                            case OK -> newStatus = TransactionStatusDto.CLOSED;
                            case KO -> newStatus = TransactionStatusDto.CLOSURE_FAILED;
                            default -> {
                                return Mono.error(new RuntimeException("Invalid outcome result enum value"));
                            }
                        }

                        TransactionClosureSendData closureSendData =
                                new TransactionClosureSendData(
                                        response.getOutcome(),
                                        newStatus
                                );

                        TransactionClosureSentEvent event = new TransactionClosureSentEvent(
                                transaction.getTransactionId().value().toString(),
                                transaction.getRptId().value(),
                                transaction.getTransactionActivatedData().getPaymentToken(),
                                closureSendData
                        );

                        return transactionEventStoreRepository.save(event);
                    });
        }
    }

    private ClosePaymentRequestDto.OutcomeEnum authorizationResultToOutcome(AuthorizationResultDto authorizationResult) {
        switch (authorizationResult) {
            case OK -> {
                return ClosePaymentRequestDto.OutcomeEnum.OK;
            }
            case KO -> {
                return ClosePaymentRequestDto.OutcomeEnum.KO;
            }
            default ->
                    throw new RuntimeException("Missing authorization result enum value mapping to Nodo closePayment outcome");
        }
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
                    throw new RuntimeException("Missing authorization result enum value mapping to Nodo closePayment outcome");
        }
    }

    // FIXME: Example aggregate building from events
    private Mono<Transaction> replayTransactionEvents(String transactionId) {
        Flux<TransactionEvent<Object>> events = eventStoreRepository.findByTransactionId(transactionId);

        return events.reduce(new EmptyTransaction(), Transaction::applyEvent);
    }
}
