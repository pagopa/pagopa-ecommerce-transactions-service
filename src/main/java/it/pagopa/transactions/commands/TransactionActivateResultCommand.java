package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.transactions.commands.data.ActivationResultData;

public final class TransactionActivateResultCommand extends TransactionsCommand<ActivationResultData> {
	public TransactionActivateResultCommand(RptId rptId, ActivationResultData data) {
		super(rptId, TransactionsCommandCode.ACTIVATE, data);
	}
}