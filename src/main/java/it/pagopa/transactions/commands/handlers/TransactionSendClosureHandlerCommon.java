package it.pagopa.transactions.commands.handlers;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.util.Optional;

@Slf4j
public abstract class TransactionSendClosureHandlerCommon
        implements
        CommandHandler<TransactionClosureSendCommand, Mono<Tuple2<Optional<BaseTransactionEvent<?>>, Either<BaseTransactionEvent<?>, BaseTransactionEvent<?>>>>> {

    protected static final String CONFERMATO = "Confermato";
    protected static final String RIFIUTATO = "Rifiutato";

    protected final PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper;
    protected final NodeForPspClient nodeForPspClient;
    protected final QueueAsyncClient closureRetryQueueAsyncClient;
    protected final QueueAsyncClient refundQueueAsyncClient;

    protected final TransactionsUtils transactionsUtils;
    protected final AuthRequestDataUtils authRequestDataUtils;

    protected final TracingUtils tracingUtils;
    protected final Integer paymentTokenValidity;
    protected final Integer retryTimeoutInterval;
    protected final Integer softTimeoutOffset;
    protected final int transientQueuesTTLSeconds;

    protected TransactionSendClosureHandlerCommon(
            TransactionsUtils transactionsUtils,
            AuthRequestDataUtils authRequestDataUtils,
            TracingUtils tracingUtils,
            Integer paymentTokenValidity,
            Integer retryTimeoutInterval,
            Integer softTimeoutOffset,
            int transientQueuesTTLSeconds,
            PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper,
            NodeForPspClient nodeForPspClient,
            QueueAsyncClient closureRetryQueueAsyncClient,
            QueueAsyncClient refundQueueAsyncClient
    ) {

        this.transactionsUtils = transactionsUtils;
        this.authRequestDataUtils = authRequestDataUtils;
        this.tracingUtils = tracingUtils;
        this.paymentTokenValidity = paymentTokenValidity;
        this.retryTimeoutInterval = retryTimeoutInterval;
        this.softTimeoutOffset = softTimeoutOffset;
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.paymentRequestInfoRedisTemplateWrapper = paymentRequestInfoRedisTemplateWrapper;
        this.nodeForPspClient = nodeForPspClient;
        this.closureRetryQueueAsyncClient = closureRetryQueueAsyncClient;
        this.refundQueueAsyncClient = refundQueueAsyncClient;
    }
}
