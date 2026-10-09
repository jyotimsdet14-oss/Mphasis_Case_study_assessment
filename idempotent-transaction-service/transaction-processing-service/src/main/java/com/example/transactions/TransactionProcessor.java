package com.example.transactions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static com.example.transactions.ProcessingResult.Status;

/** In-memory batch processor. The state store is deliberately replaceable. */
public final class TransactionProcessor {
    private static final Logger LOG = Logger.getLogger(TransactionProcessor.class.getName());
    private final int maxAttempts;
    private final Map<String, BigDecimal> balances = new HashMap<>();
    private final Set<String> processedTransactions = new HashSet<>();
    private final Set<String> seenRequests = new HashSet<>();

    public TransactionProcessor(int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
    }

    public BatchReport process(List<Transaction> input) {
        List<ProcessingResult> results = new ArrayList<>();
        Map<String, List<Transaction>> byAccount = new LinkedHashMap<>();
        Set<String> batchTransactionIds = new HashSet<>();

        // Validate and deduplicate before ordering. A malformed row cannot block the batch.
        for (Transaction tx : input) {
            String invalid = validate(tx);
            if (invalid != null) {
                results.add(result(tx, Status.FAILED, 0, invalid));
                LOG.warning("transaction=" + safe(tx.transactionId()) + " status=FAILED reason=" + invalid);
                continue;
            }
            if (!seenRequests.add(tx.requestId()) || processedTransactions.contains(tx.transactionId())
                    || !batchTransactionIds.add(tx.transactionId())) {
                results.add(result(tx, Status.DUPLICATE, 0, "Request or business transaction already seen"));
                LOG.info("transaction=" + safe(tx.transactionId()) + " status=DUPLICATE");
                continue;
            }
            LOG.info("transaction=" + safe(tx.transactionId()) + " status=RECEIVED");
            byAccount.computeIfAbsent(tx.accountId(), ignored -> new ArrayList<>()).add(tx);
        }

        // Per-account sequence ordering. The earliest sequence in this batch establishes
        // the starting point; gaps inside the batch are reported as PENDING.
        for (List<Transaction> accountTransactions : byAccount.values()) {
            accountTransactions.sort(Comparator.comparingLong(Transaction::sequence)
                    .thenComparing(Transaction::transactionId));
            long expected = accountTransactions.get(0).sequence();
            long lastSeenSequence = -1;
            for (Transaction tx : accountTransactions) {
                if (tx.sequence() == lastSeenSequence || tx.sequence() < expected) {
                    results.add(result(tx, Status.FAILED, 0, "Sequence is stale or conflicts with another event"));
                    LOG.warning("transaction=" + safe(tx.transactionId()) + " status=FAILED reason=sequence_conflict");
                    continue;
                }
                lastSeenSequence = tx.sequence();
                if (tx.sequence() > expected) {
                    results.add(result(tx, Status.PENDING, 0, "Waiting for sequence " + expected));
                    LOG.info("transaction=" + safe(tx.transactionId()) + " status=PENDING missingSequence=" + expected);
                    continue;
                }
                ProcessingResult outcome = applyWithRetry(tx);
                results.add(outcome);
                if (outcome.status() == Status.PROCESSED) expected = tx.sequence() + 1;
                // A failed sequence blocks later events for this account until recovery.
            }
        }
        return new BatchReport(List.copyOf(results), Map.copyOf(balances));
    }

    private ProcessingResult applyWithRetry(Transaction tx) {
        int attempts = 0;
        while (attempts < maxAttempts) {
            attempts++;
            try {
                LOG.info("transaction=" + safe(tx.transactionId()) + " status=PROCESSING attempt=" + attempts);
                // Deterministic fault injection: fail once for flagged transactions.
                if (tx.simulateTransientFailure() && attempts == 1)
                    throw new TransientProcessingException("Simulated temporary failure");
                BigDecimal delta = tx.type() == Transaction.Type.CREDIT ? tx.amount() : tx.amount().negate();
                BigDecimal newBalance = balances.getOrDefault(tx.accountId(), BigDecimal.ZERO).add(delta);
                // Commit side effect and idempotency marker together within this in-memory critical section.
                balances.put(tx.accountId(), newBalance);
                processedTransactions.add(tx.transactionId());
                LOG.info("transaction=" + safe(tx.transactionId()) + " status=PROCESSED attempts=" + attempts);
                return result(tx, Status.PROCESSED, attempts, "Applied successfully");
            } catch (TransientProcessingException ex) {
                LOG.warning("transaction=" + safe(tx.transactionId()) + " status=RETRY_PENDING attempt=" + attempts);
            } catch (RuntimeException ex) {
                LOG.severe("transaction=" + safe(tx.transactionId()) + " status=FAILED error=" + ex.getClass().getSimpleName());
                return result(tx, Status.FAILED, attempts, "Processing failed safely");
            }
        }
        return result(tx, Status.FAILED, attempts, "Retry limit exhausted");
    }

    private static String validate(Transaction tx) {
        if (tx == null) return "Transaction is missing";
        if (blank(tx.transactionId()) || blank(tx.requestId()) || blank(tx.accountId())) return "Required identifier is missing";
        if (tx.sequence() < 1) return "Sequence must be positive";
        if (tx.type() == null) return "Transaction type is required";
        if (tx.amount() == null || tx.amount().signum() <= 0) return "Amount must be greater than zero";
        if (blank(tx.currency()) || !tx.currency().matches("[A-Z]{3}")) return "Currency must be a 3-letter code";
        return null;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String safe(String value) { return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_-]", "_"); }
    private static ProcessingResult result(Transaction tx, Status status, int attempts, String message) {
        return new ProcessingResult(tx == null ? "unknown" : tx.transactionId(),
                tx == null ? "unknown" : tx.requestId(), status, attempts, message);
    }

    public record BatchReport(List<ProcessingResult> results, Map<String, BigDecimal> balances) {
        public Map<Status, Long> counts() {
            Map<Status, Long> counts = new LinkedHashMap<>();
            for (Status status : Status.values()) counts.put(status,
                    results.stream().filter(r -> r.status() == status).count());
            return Map.copyOf(counts);
        }
    }

    private static final class TransientProcessingException extends RuntimeException {
        TransientProcessingException(String message) { super(message); }
    }
}
