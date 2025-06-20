package it.pagopa.transactions.repositories;

import it.pagopa.ecommerce.commons.annotations.ValueObject;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.lang.NonNull;

@ValueObject
public record WalletPaymentInfo(
        @NonNull String sessionId,
        @NonNull String securityToken,
        @NonNull String orderId

) {
    /**
     * Structure to identify the card data information.
     *
     * @param sessionId     npg session id
     * @param securityToken npg security token
     * @param orderId       npg orderId
     */
    @PersistenceCreator
    public WalletPaymentInfo {
        // Do nothing
    }
}
