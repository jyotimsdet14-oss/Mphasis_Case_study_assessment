package com.example.transactions;

import java.math.BigDecimal;
import java.util.List;

import static com.example.transactions.ProcessingResult.Status;

/** Dependency-free executable checks: run with java -ea. */
public final class ProcessorSelfTest {
    public static void main(String[] args) {
        outOfOrderAndDuplicateAreSafe();
        invalidRecordIsIsolated();
        transientFailureRetriesWithoutDoubleApplying();
        sequenceGapIsPending();
        System.out.println("All processor checks passed.");
    }

    private static void outOfOrderAndDuplicateAreSafe() {
        var p = new TransactionProcessor(3);
        var report = p.process(List.of(tx("T2", "R2", 2, "A", Transaction.Type.DEBIT, "40", false),
                tx("T1", "R1", 1, "A", Transaction.Type.CREDIT, "100", false),
                tx("T2", "R2-DUP", 2, "A", Transaction.Type.DEBIT, "40", false)));
        assert report.results().stream().filter(r -> r.transactionId().equals("T2") && r.status() == Status.PROCESSED).count() == 1;
        assert report.results().stream().anyMatch(r -> r.requestId().equals("R2-DUP") && r.status() == Status.DUPLICATE);
        assert report.balances().get("A").compareTo(new BigDecimal("60")) == 0;
    }

    private static void invalidRecordIsIsolated() {
        var report = new TransactionProcessor(3).process(List.of(
                tx("BAD", "RB", 1, "A", Transaction.Type.DEBIT, "-10", false),
                tx("GOOD", "RG", 1, "B", Transaction.Type.CREDIT, "5", false)));
        assert report.results().stream().anyMatch(r -> r.transactionId().equals("BAD") && r.status() == Status.FAILED);
        assert report.balances().get("B").compareTo(new BigDecimal("5")) == 0;
    }

    private static void transientFailureRetriesWithoutDoubleApplying() {
        var report = new TransactionProcessor(3).process(List.of(
                tx("RETRY", "RR", 5, "C", Transaction.Type.CREDIT, "500", true)));
        var result = report.results().get(0);
        assert result.status() == Status.PROCESSED && result.attempts() == 2;
        assert report.balances().get("C").compareTo(new BigDecimal("500")) == 0;
    }

    private static void sequenceGapIsPending() {
        var report = new TransactionProcessor(3).process(List.of(
                tx("T1", "R1", 1, "A", Transaction.Type.CREDIT, "10", false),
                tx("T3", "R3", 3, "A", Transaction.Type.CREDIT, "10", false)));
        assert report.results().stream().anyMatch(r -> r.transactionId().equals("T3") && r.status() == Status.PENDING);
        assert report.balances().get("A").compareTo(new BigDecimal("10")) == 0;
    }

    private static Transaction tx(String id, String request, long seq, String account,
                                  Transaction.Type type, String amount, boolean failOnce) {
        return new Transaction(id, request, seq, account, type, new BigDecimal(amount), "NZD", failOnce);
    }
}
