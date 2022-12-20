package it.pagopa.transactions.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class EuroUtils {
    private EuroUtils() {
        throw new IllegalStateException("Utility EuroUtils class");
    }

    public static BigDecimal euroCentsToEuro(Integer euroCents) {
        return BigDecimal.valueOf(euroCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
