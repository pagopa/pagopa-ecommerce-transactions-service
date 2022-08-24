package it.pagopa.transactions.repositories;

import it.pagopa.transactions.domain.RptId;
import org.springframework.data.repository.CrudRepository;

public interface PaymentRequestsInfoRepository extends CrudRepository<PaymentRequestInfo
        , RptId> {
}
