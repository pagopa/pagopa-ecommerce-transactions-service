package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionAuthorizeCommand;
import it.pagopa.transactions.documents.TransactionAuthorizationData;
import it.pagopa.transactions.documents.TransactionAuthorizationEvent;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionAuthorizeHandler implements CommandHandler<TransactionAuthorizeCommand, Mono<RequestAuthorizationResponseDto>> {
    @Autowired
    private PaymentGatewayClient paymentGatewayClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationData> transactionEventStoreRepository;

    @Override
    public Mono<RequestAuthorizationResponseDto> handle(TransactionAuthorizeCommand command) {
        return paymentGatewayClient.requestAuthorization(command.getData())
                .flatMap(auth -> {
                    log.info("Logging authorization event for rpt id {}", command.getRptId().value());

                    TransactionAuthorizationEvent authorizationEvent = new TransactionAuthorizationEvent(
                            command.getRptId().value(),
                            command.getData().transaction().getPaymentToken().value(),
                            new TransactionAuthorizationData(command.getData().fee(), command.getData().paymentInstrumentId(), command.getData().pspId())
                    );

                    return transactionEventStoreRepository.save(authorizationEvent).thenReturn(auth);
                });
    }
}
