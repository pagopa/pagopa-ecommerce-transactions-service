package it.pagopa.transactions.configurations;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.utils.NpgApiKeyConfiguration;
import it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.function.Function;

/**
 * Configuration class used to read all PSP api keys for every payment method
 */
@Configuration
@Slf4j
public class NpgPspApiKeysConfigBuilder {

    /**
     * ObjectMapper instance used to decode JSON string configuration
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Return a map where valued with each psp id - api keys entries
     *
     * @param apiKeys - the secret api keys configuration json
     * @return the parsed map
     */
    @Bean
    public it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig npgCardsApiKeys(
                                                                                 @Value(
                                                                                     "${npg.authorization.cards.keys}"
                                                                                 ) String apiKeys,
                                                                                 @Value(
                                                                                     "${npg.authorization.cards.pspList}"
                                                                                 ) Set<String> pspToHandle
    ) {
        return parseApiKeysMap(
                apiKeys,
                pspToHandle,
                NpgClient.PaymentMethod.CARDS
        );
    }

    /**
     * Return a map where valued with each psp id - api keys entries
     *
     * @param apiKeys - the secret api keys configuration json
     * @return the parsed map
     */
    @Bean
    public it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig npgPaypalApiKeys(
                                                                                  @Value(
                                                                                      "${npg.authorization.paypal.keys}"
                                                                                  ) String apiKeys,
                                                                                  @Value(
                                                                                      "${npg.authorization.paypal.pspList}"
                                                                                  ) Set<String> pspToHandle
    ) {
        return parseApiKeysMap(
                apiKeys,
                pspToHandle,
                NpgClient.PaymentMethod.PAYPAL
        );
    }

    /**
     * Return a map where valued with each psp id - api keys entries
     *
     * @param apiKeys - the secret api keys configuration json
     * @return the parsed map
     */
    @Bean
    public it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig npgMyBankApiKeys(
                                                                                  @Value(
                                                                                      "${npg.authorization.mybank.keys}"
                                                                                  ) String apiKeys,
                                                                                  @Value(
                                                                                      "${npg.authorization.mybank.pspList}"
                                                                                  ) Set<String> pspToHandle
    ) {
        return parseApiKeysMap(
                apiKeys,
                pspToHandle,
                NpgClient.PaymentMethod.MYBANK
        );
    }

    /**
     * Return a map where valued with each psp id - api keys entries
     *
     * @param apiKeys - the secret api keys configuration json
     * @return the parsed map
     */
    @Bean
    public it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig npgBancomatpayApiKeys(
                                                                                       @Value(
                                                                                           "${npg.authorization.bancomatpay.keys}"
                                                                                       ) String apiKeys,
                                                                                       @Value(
                                                                                           "${npg.authorization.bancomatpay.pspList}"
                                                                                       ) Set<String> pspToHandle
    ) {
        return parseApiKeysMap(
                apiKeys,
                pspToHandle,
                NpgClient.PaymentMethod.BANCOMATPAY
        );
    }

    /**
     * Return a map where valued with each psp id - api keys entries
     *
     * @param apiKeys - the secret api keys configuration json
     * @return the parsed map
     */
    @Bean
    public it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig npgApplePayApiKeys(
                                                                                    @Value(
                                                                                        "${npg.authorization.applepay.keys}"
                                                                                    ) String apiKeys,
                                                                                    @Value(
                                                                                        "${npg.authorization.applepay.pspList}"
                                                                                    ) Set<String> pspToHandle
    ) {
        return parseApiKeysMap(
                apiKeys,
                pspToHandle,
                NpgClient.PaymentMethod.APPLEPAY
        );
    }

    /**
     * Return a map where valued with each psp id - api keys entries
     *
     * @param apiKeys - the secret api keys configuration json
     * @return the parsed map
     */
    @Bean
    public it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig npgSatispayApiKeys(
                                                                                    @Value(
                                                                                        "${npg.authorization.satispay.keys}"
                                                                                    ) String apiKeys,
                                                                                    @Value(
                                                                                        "${npg.authorization.satispay.pspList}"
                                                                                    ) Set<String> pspToHandle
    ) {
        return parseApiKeysMap(
                apiKeys,
                pspToHandle,
                NpgClient.PaymentMethod.SATISPAY
        );
    }

    /**
     * Return a map where valued with each psp id - api keys entries
     *
     * @param apiKeys - the secret api keys configuration json
     * @return the parsed map
     */
    @Bean
    public it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig npgGooglePayApiKeys(
                                                                                     @Value(
                                                                                         "${npg.authorization.googlepay.keys}"
                                                                                     ) String apiKeys,
                                                                                     @Value(
                                                                                         "${npg.authorization.googlepay.pspList}"
                                                                                     ) Set<String> pspToHandle
    ) {
        return parseApiKeysMap(
                apiKeys,
                pspToHandle,
                NpgClient.PaymentMethod.GOOGLEPAY
        );
    }

    @Bean
    public NpgApiKeyConfiguration npgApiKeyConfiguration(
                                                         NpgPspApiKeysConfig npgCardsApiKeys,
                                                         NpgPspApiKeysConfig npgBancomatpayApiKeys,
                                                         NpgPspApiKeysConfig npgMyBankApiKeys,
                                                         NpgPspApiKeysConfig npgPaypalApiKeys,
                                                         NpgPspApiKeysConfig npgApplePayApiKeys,
                                                         NpgPspApiKeysConfig npgSatispayApiKeys,
                                                         NpgPspApiKeysConfig npgGooglePayApiKeys,
                                                         @Value("${npg.client.apiKey}") String defaultApiKey
    ) {
        return new NpgApiKeyConfiguration.Builder()
                .setDefaultApiKey(defaultApiKey)
                .withMethodPspMapping(NpgClient.PaymentMethod.CARDS, npgCardsApiKeys)
                .withMethodPspMapping(NpgClient.PaymentMethod.BANCOMATPAY, npgBancomatpayApiKeys)
                .withMethodPspMapping(NpgClient.PaymentMethod.MYBANK, npgMyBankApiKeys)
                .withMethodPspMapping(NpgClient.PaymentMethod.PAYPAL, npgPaypalApiKeys)
                .withMethodPspMapping(NpgClient.PaymentMethod.APPLEPAY, npgApplePayApiKeys)
                .withMethodPspMapping(NpgClient.PaymentMethod.SATISPAY, npgSatispayApiKeys)
                .withMethodPspMapping(NpgClient.PaymentMethod.GOOGLEPAY, npgGooglePayApiKeys)
                .build();
    }

    /**
     * Return a map where valued with each psp id - api keys entries
     *
     * @param apiKeys - the secret api keys configuration json
     * @return the parsed map
     */
    private it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig parseApiKeysMap(

                                                                                  String apiKeys,
                                                                                  Set<String> pspToHandle,
                                                                                  NpgClient.PaymentMethod paymentMethod

    ) {
        return it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig.parseApiKeyConfiguration(
                apiKeys,
                pspToHandle,
                paymentMethod,
                objectMapper
        )
                .fold(exception -> {
                    throw exception;
                },
                        Function.identity()
                );
    }

}
