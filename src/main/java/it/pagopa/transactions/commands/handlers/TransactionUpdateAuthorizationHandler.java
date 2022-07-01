package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v1.dto.AdditionalPaymentInformationsDto;
import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentRequestDto;
import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionInfoDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestData;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdateData;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionEventCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

@Component
@Slf4j
public class TransactionUpdateAuthorizationHandler implements CommandHandler<TransactionUpdateAuthorizationCommand, Mono<TransactionInfoDto>> {

    @Autowired
    NodeForPspClient nodeForPspClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationStatusUpdateData> transactionEventStoreRepository;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestedEvent> authorizationRequestedEventStoreRepository;

    @Override
    public Mono<TransactionInfoDto> handle(TransactionUpdateAuthorizationCommand command) {
        Transaction transaction = command.getData().transaction();

        if (transaction.getStatus() != TransactionStatusDto.AUTHORIZATION_REQUESTED) {
            log.error("Error: requesting authorization update for transaction in state {}", transaction.getStatus());
            return Mono.error(new AlreadyProcessedException(transaction.getRptId()));
        } else {
            UpdateAuthorizationRequestDto updateAuthorizationRequest = command.getData().updateAuthorizationRequest();

            return authorizationRequestedEventStoreRepository.findByPaymentTokenAndEventCode(
                            transaction.getPaymentToken().value(),
                            TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT
                    )
                    .flatMap(authorizationRequestedEvent -> {
                        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData().getData();

                        ClosePaymentRequestDto closePaymentRequest = new ClosePaymentRequestDto()
                                .paymentTokens(List.of(transaction.getPaymentToken().value()))
                                .outcome(authorizationResultToOutcome(updateAuthorizationRequest.getAuthorizationResult()))
                                .identificativoPsp(authorizationRequestData.getPspId())
                                .tipoVersamento(ClosePaymentRequestDto.TipoVersamentoEnum.fromValue(authorizationRequestData.getPaymentTypeCode()))
                                .identificativoIntermediario(authorizationRequestData.getBrokerName())
                                .identificativoCanale(authorizationRequestData.getPspChannelCode())
                                .pspTransactionId(authorizationRequestData.getTransactionId().toString())
                                .totalAmount(new BigDecimal(transaction.getAmount().value() + authorizationRequestData.getFee()))
                                .fee(new BigDecimal(authorizationRequestData.getFee()))
                                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                                .additionalPaymentInformations(
                                        new AdditionalPaymentInformationsDto()
                                                .outcomePaymentGateway(updateAuthorizationRequest.getAuthorizationResult().toString())
                                                .transactionId(authorizationRequestData.getTransactionId().toString())
                                                .authorizationCode(updateAuthorizationRequest.getAuthorizationCode())
                                );

                        return nodeForPspClient.closePayment(closePaymentRequest);
                    })
                    .flatMap(response -> {
                        TransactionStatusDto newStatus;

                        switch (response.getEsito()) {
                            case OK -> newStatus = TransactionStatusDto.AUTHORIZED;
                            case KO -> newStatus = TransactionStatusDto.AUTHORIZATION_FAILED;
                            default -> {
                                return Mono.error(new RuntimeException("Invalid authorization result enum value"));
                            }
                        }

                        TransactionAuthorizationStatusUpdateData statusUpdateData =
                                new TransactionAuthorizationStatusUpdateData(
                                        updateAuthorizationRequest.getAuthorizationResult(),
                                        response.getEsito(),
                                        newStatus
                                );

                        TransactionAuthorizationStatusUpdatedEvent event = new TransactionAuthorizationStatusUpdatedEvent(
                                transaction.getRptId().toString(),
                                transaction.getPaymentToken().toString(),
                                statusUpdateData
                        );

                        return transactionEventStoreRepository.save(event).thenReturn(newStatus);
                    })
                    .map(newStatus -> new TransactionInfoDto()
                            .amount(transaction.getAmount().value())
                            .reason(transaction.getDescription().value())
                            .paymentToken(transaction.getPaymentToken().value())
                            .authToken(null)
                            .rptId(transaction.getRptId().value())
                            .status(newStatus));
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
}
