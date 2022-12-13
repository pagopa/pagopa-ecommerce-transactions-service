package it.pagopa.transactions.projections;

import it.pagopa.ecommerce.commons.domain.RptId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Generated;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Generated
public class TransactionsProjection<T> {
    
    private RptId rptId;
    private T data;
}
