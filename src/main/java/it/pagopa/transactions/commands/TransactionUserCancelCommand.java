package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.v2.RptId;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

@Getter
@EqualsAndHashCode(callSuper = true)
public final class TransactionUserCancelCommand extends TransactionsCommand<TransactionId> {

    private final UUID xUserId;

    public TransactionUserCancelCommand(
            List<RptId> rptIds,
            TransactionId data,
            @Nullable UUID xUserId
    ) {
        super(rptIds, TransactionsCommandCode.USER_CANCEL_REQUEST, data);
        this.xUserId = xUserId;
    }
}
