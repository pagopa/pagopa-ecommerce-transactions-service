package it.pagopa.transactions.utils;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.Optional;

public sealed interface PaymentSessionData permits PaymentSessionData.ApmSessionData,PaymentSessionData.CardSessionData,PaymentSessionData.PgsCardSessionData,PaymentSessionData.RedirectSessionData,PaymentSessionData.WalletCardSessionData,PaymentSessionData.WalletPayPalSessionData {

    @Nonnull
    String brand();

    record PgsCardSessionData(
            @Nonnull String brand,
            @Nonnull String cardBin,
            @Nonnull String lastFourDigits
    )
            implements
            PaymentSessionData {
        public PgsCardSessionData {
            Objects.requireNonNull(brand);
            Objects.requireNonNull(cardBin);
            Objects.requireNonNull(lastFourDigits);
        }
    }

    record CardSessionData(
            @Nonnull String brand,
            @Nonnull String sessionId,
            @Nonnull String cardBin,
            @Nonnull String lastFourDigits
    )
            implements
            PaymentSessionData {
        public CardSessionData {
            Objects.requireNonNull(brand);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(cardBin);
            Objects.requireNonNull(lastFourDigits);
        }
    }

    record ApmSessionData(
            @Nonnull String brand
    )
            implements
            PaymentSessionData {
        public ApmSessionData {
            Objects.requireNonNull(brand);
        }
    }

    record WalletCardSessionData(
            @Nonnull String brand,
            @Nonnull Optional<String> sessionId,
            @Nonnull String cardBin,
            @Nonnull String lastFourDigits,
            @Nonnull String contractId
    )
            implements
            PaymentSessionData {
        public WalletCardSessionData {
            Objects.requireNonNull(brand);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(cardBin);
            Objects.requireNonNull(lastFourDigits);
            Objects.requireNonNull(contractId);
        }
    }

    record WalletPayPalSessionData(
            @Nonnull String contractId,
            @Nonnull String maskedEmail
    )
            implements
            PaymentSessionData {
        public WalletPayPalSessionData {
            Objects.requireNonNull(contractId);
            Objects.requireNonNull(maskedEmail);
        }

        @Nonnull
        @Override
        public String brand() {
            return "PAYPAL";
        }
    }

    record RedirectSessionData()
            implements
            PaymentSessionData {
        @Nonnull
        @Override
        public String brand() {
            return "N/A";
        }
    }
}
