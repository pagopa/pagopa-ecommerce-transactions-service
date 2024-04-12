package it.pagopa.transactions.commands.data;

import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.BundleDto;
import it.pagopa.transactions.utils.PaymentSessionData;

import java.util.Map;
import java.util.Optional;

/**
 * Authorization request session data. This record contains payment session data
 * along with psp data
 *
 * @param paymentMethodName        the payment method name
 * @param paymentMethodDescription the payment method description string
 * @param bundle                   the gec bundle matching the authorization
 *                                 request psp
 * @param paymentSessionData       data about the specific payment method and
 *                                 authorization (e.g. wallet, card, apm)
 */
public record AuthorizationRequestSessionData(
        String paymentMethodName,
        String paymentMethodDescription,
        Optional<BundleDto> bundle,
        PaymentSessionData paymentSessionData,
        String asset,
        Map<String, String> brandAssets
) {
}
