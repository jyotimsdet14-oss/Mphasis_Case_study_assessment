package com.example.transactions;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java -cp out com.example.transactions.Main <transactions.csv>");
            System.exit(2);
        }
        List<Transaction> transactions = readCsv(Path.of(args[0]));
        TransactionProcessor.BatchReport report = new TransactionProcessor(3).process(transactions);
        System.out.println("RESULTS");
        report.results().forEach(r -> System.out.printf("%s,%s,%s,%d,%s%n", r.transactionId(), r.requestId(), r.status(), r.attempts(), r.message()));
        System.out.println("COUNTS");
        report.counts().forEach((status, count) -> System.out.println(status + "=" + count));
        System.out.println("BALANCES");
        report.balances().forEach((account, balance) -> System.out.println(account + "=" + balance.stripTrailingZeros().toPlainString()));
    }

    static List<Transaction> readCsv(Path path) throws IOException {
        List<Transaction> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("transactionId,")) continue;
                try { rows.add(Transaction.fromCsv(line.split(",", -1))); }
                catch (RuntimeException ex) {
                    rows.add(new Transaction("INVALID_LINE_" + lineNumber, "INVALID_LINE_" + lineNumber,
                            0, "", null, BigDecimal.ZERO, "", false));
                }
            }
        }
        return rows;
    }
}
