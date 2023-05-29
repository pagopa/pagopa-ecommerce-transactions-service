package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;

@Component
@Slf4j
public class AuthorizationUpdateProjectionHandler
        implements ProjectionHandler<TransactionAuthorizationCompletedEvent, Mono<TransactionActivated>> {

    private final TransactionsViewRepository transactionsViewRepository;

    private final Integer paymentTokenValidity;

    @Autowired
    public AuthorizationUpdateProjectionHandler(
            TransactionsViewRepository transactionsViewRepository,
            @Value("${payment.token.validity}") Integer paymentTokenValidity
    ) {
        this.transactionsViewRepository = transactionsViewRepository;
        this.paymentTokenValidity = paymentTokenValidity;
    }

    @Override
    public Mono<TransactionActivated> handle(TransactionAuthorizationCompletedEvent data) {
        return transactionsViewRepository.findById(data.getTransactionId())
                .switchIfEmpty(
                        Mono.error(new TransactionNotFoundException(data.getTransactionId()))
                )
                .flatMap(transactionDocument -> {
                    transactionDocument.setRrn(data.getData().getRrn());
                    transactionDocument.setStatus(TransactionStatusDto.AUTHORIZATION_COMPLETED);
                    transactionDocument.setAuthorizationCode(data.getData().getAuthorizationCode());
                    transactionDocument.setAuthorizationErrorCode(data.getData().getErrorCode());

                    return transactionsViewRepository.save(transactionDocument);
                })
                .map(
                        transactionDocument -> new TransactionActivated(
                                new TransactionId(transactionDocument.getTransactionId()),
                                transactionDocument.getPaymentNotices().stream()
                                        .map(
                                                paymentNotice -> new PaymentNotice(
                                                        new PaymentToken(paymentNotice.getPaymentToken()),
                                                        new RptId(paymentNotice.getRptId()),
                                                        new TransactionAmount(paymentNotice.getAmount()),
                                                        new TransactionDescription(paymentNotice.getDescription()),
                                                        new PaymentContextCode(paymentNotice.getPaymentContextCode()),
                                                        paymentNotice.getTransferList().stream()
                                                                .map(
                                                                        p -> new PaymentTransferInfo(
                                                                                p.getPaFiscalCode(),
                                                                                p.getDigitalStamp(),
                                                                                p.getTransferAmount(),
                                                                                p.getTransferCategory()
                                                                        )
                                                                ).toList()
                                                )
                                        ).toList(),
                                transactionDocument.getEmail(),
                                null,
                                null,
                                ZonedDateTime.parse(transactionDocument.getCreationDate()),
                                transactionDocument.getClientId(),
                                transactionDocument.getIdCart(),
                                paymentTokenValidity
                        )
                );
    }

}
