package it.pagopa.transactions.commands.data;

import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.BundleDto;

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
 * @param npgSessionId             the NPG session id
 * @param brand                    the brand associated to the payment method
 * @param npgContractId            the NPG contract id
 */
public record AuthorizationRequestSessionData(
        String paymentMethodName,
        String paymentMethodDescription,
        Optional<BundleDto> bundle,
        String brand,
        Optional<String> npgSessionId,
        Optional<String> npgContractId,

        String asset,

        Map<String, String> brandAssets
) {
}
