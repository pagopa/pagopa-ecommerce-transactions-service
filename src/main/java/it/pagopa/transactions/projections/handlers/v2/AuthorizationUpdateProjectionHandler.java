package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.PgsTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.domain.v2.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.ZonedDateTime;
import java.util.Optional;

import static it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler.QUALIFIER_NAME;

@Component(QUALIFIER_NAME)
@Slf4j
public class AuthorizationUpdateProjectionHandler
        implements ProjectionHandler<TransactionAuthorizationCompletedEvent, Mono<TransactionActivated>> {

    public static final String QUALIFIER_NAME = "authorizationUpdateProjectionHandlerV2";
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
                .cast(Transaction.class)
                .flatMap(transactionDocument -> {
                    transactionDocument.setRrn(data.getData().getRrn());
                    transactionDocument.setStatus(TransactionStatusDto.AUTHORIZATION_COMPLETED);
                    transactionDocument.setAuthorizationCode(data.getData().getAuthorizationCode());

                    Tuple2<String, Optional<String>> gatewayStatusAndErrorCode = switch (data.getData().getTransactionGatewayAuthorizationData()) {
                        case NpgTransactionGatewayAuthorizationData npgData -> Tuples.of(
                                npgData.getOperationResult().toString(),
                                Optional.ofNullable(npgData.getErrorCode())
                        );
                        case RedirectTransactionGatewayAuthorizationData redirectData -> Tuples.of(
                                redirectData.getOutcome().toString(),
                                Optional.ofNullable(redirectData.getErrorCode())
                        );
                        case PgsTransactionGatewayAuthorizationData pgsData -> throw new IllegalArgumentException("Pgs authorization complete data not handled!");
                    };
                    transactionDocument.setAuthorizationErrorCode(gatewayStatusAndErrorCode.getT2().orElse(null));
                    transactionDocument.setGatewayAuthorizationStatus(gatewayStatusAndErrorCode.getT1());

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
                                                                ).toList(),
                                                        paymentNotice.isAllCCP(),
                                                        new CompanyName(paymentNotice.getCompanyName()),
                                                        paymentNotice.getCreditorReferenceId()
                                                )
                                        ).toList(),
                                transactionDocument.getEmail(),
                                null,
                                null,
                                ZonedDateTime.parse(transactionDocument.getCreationDate()),
                                transactionDocument.getClientId(),
                                transactionDocument.getIdCart(),
                                paymentTokenValidity,
                                new EmptyTransactionGatewayActivationData(), // FIXME
                                transactionDocument.getUserId()
                        )
                );
    }

}
