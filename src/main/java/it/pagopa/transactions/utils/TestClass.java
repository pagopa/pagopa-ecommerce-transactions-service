package it.pagopa.transactions.utils;

import ch.qos.logback.core.net.SyslogOutputStream;

import java.math.BigDecimal;

public class TestClass {

    public static void main(String[] args) {
        BigDecimal bigDecimal = new BigDecimal(10.25);
        System.out.println(bigDecimal);
        System.out.println(bigDecimal.intValue());
        System.out.println(bigDecimal.multiply(BigDecimal.valueOf(100)).intValue());
    }

}
