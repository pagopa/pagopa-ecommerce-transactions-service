package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
public abstract class TransactionUpdateAuthorizationHandlerCommon
        implements CommandHandler<TransactionUpdateAuthorizationCommand, Mono<BaseTransactionEvent<?>>> {
    protected final AuthRequestDataUtils extractAuthRequestData;

    protected final TransactionsUtils transactionsUtils;

    protected TransactionUpdateAuthorizationHandlerCommon(
            AuthRequestDataUtils extractAuthRequestData,
            TransactionsUtils transactionsUtils
    ) {
        this.extractAuthRequestData = extractAuthRequestData;

        this.transactionsUtils = transactionsUtils;
    }
}
