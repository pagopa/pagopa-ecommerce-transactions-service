package it.pagopa.transactions.utils;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class UrlUtils {

    public static boolean urlsEqualsWithRandomParam(
                                                    String actual,
                                                    String expected
    ) {
        URI actualUri = URI.create(actual);
        URI expectedUri = URI.create(expected);

        // Compare full path
        if (!Objects.equals(actualUri.getHost(), expectedUri.getHost()) ||
                actualUri.getPort() != expectedUri.getPort() ||
                !actualUri.getPath().equals(expectedUri.getPath()))
            return false;

        // Compare fragments
        if (!getParametersAsMap(actualUri.getFragment()).equals(getParametersAsMap(expectedUri.getFragment())))
            return false;

        // Compare query params without random, if are equals complete url is equals
        return getParametersAsMap(actualUri.getQuery(), true).equals(getParametersAsMap(expectedUri.getQuery(), true));
    }

    public static Map<String, String> getParametersAsMap(String query) {
        return getParametersAsMap(query, false);
    }

    public static Map<String, String> getParametersAsMap(
                                                         String query,
                                                         boolean removeRandom
    ) {
        if (query == null || query.isEmpty()) {
            return Map.of();
        }

        return Arrays.stream(query.split("&"))
                .map(param -> param.split("="))
                // remove t queryparam
                .filter(pair -> !removeRandom || !pair[0].equals("t"))
                .collect(
                        Collectors.toMap(
                                pair -> pair[0],
                                pair -> pair.length > 1 ? pair[1] : ""
                        )
                );
    }
}
