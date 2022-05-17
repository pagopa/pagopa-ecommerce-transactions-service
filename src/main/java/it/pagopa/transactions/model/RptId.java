package it.pagopa.transactions.model;

import java.util.Objects;
import java.util.regex.Pattern;

public final class RptId {
    private static final Pattern rptIdRegex = Pattern.compile("([a-zA-Z\\d]{1,35})|(RF\\d{2}[a-zA-Z\\d]{1,21})");
    private final String rptId;

    public RptId(String rptId) {
        if (!rptIdRegex.matcher(rptId).matches()) {
            throw new IllegalArgumentException("Ill-formed RPT id: " + rptId + ". Doesn't match format: " + rptIdRegex.pattern());
        }

        this.rptId = rptId;
    }

    public String getRptId() {
        return rptId;
    }

    public String getFiscalCode() {
        return rptId.substring(0, 11);
    }

    public String getNoticeId() {
        return rptId.substring(11);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (RptId) obj;
        return Objects.equals(this.rptId, that.rptId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rptId);
    }

    @Override
    public String toString() {
        return "RptId[" +
                "rptId=" + rptId + ']';
    }


}
