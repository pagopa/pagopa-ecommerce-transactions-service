package it.pagopa.transactions.commands;

import it.pagopa.transactions.domain.RptId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Generated;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Generated
public class TransactionsCommand<T> {

    private RptId rptId;
    private TransactionsCommandCode code;
    private T data;
}
