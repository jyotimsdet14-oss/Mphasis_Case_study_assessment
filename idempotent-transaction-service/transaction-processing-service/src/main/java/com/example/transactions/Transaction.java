package com.example.transactions;

import java.math.BigDecimal;

public record Transaction(
        String transactionId,
        String requestId,
        long sequence,
        String accountId,
        Type type,
        BigDecimal amount,
        String currency,
        boolean simulateTransientFailure) {

    public enum Type { CREDIT, DEBIT }

    public static Transaction fromCsv(String[] fields) {
        if (fields.length != 8) throw new IllegalArgumentException("Expected 8 CSV fields");
        return new Transaction(fields[0].trim(), fields[1].trim(), Long.parseLong(fields[2].trim()),
                fields[3].trim(), Type.valueOf(fields[4].trim().toUpperCase()),
                new BigDecimal(fields[5].trim()), fields[6].trim(),
                Boolean.parseBoolean(fields[7].trim()));
    }
}
