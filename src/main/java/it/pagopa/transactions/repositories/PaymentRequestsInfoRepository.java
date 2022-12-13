package it.pagopa.transactions.repositories;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import org.springframework.data.repository.CrudRepository;

public interface PaymentRequestsInfoRepository extends CrudRepository<PaymentRequestInfo
        , RptId> {
}
