# Idempotent Background Transaction Processing Service

A dependency-free Java 17 assessment solution. It reads a CSV batch, validates each row, orders transactions by account sequence, retries a simulated transient failure, and prints per-record outcomes, status counts, and resulting account balances.

## Run it

From this directory:

```bash
mkdir -p out
javac -d out src/main/java/com/example/transactions/*.java src/test/java/com/example/transactions/ProcessorSelfTest.java
java -ea -cp out com.example.transactions.ProcessorSelfTest
java -cp out com.example.transactions.Main sample/transactions.csv
```

No external dependencies or paid services are needed. Java 17 or newer is required.

## Input

CSV columns: `transactionId,requestId,sequence,accountId,type,amount,currency,simulateTransientFailure`. Supported types are `CREDIT` and `DEBIT`. The last column is a deterministic fault-injection switch used for the assessment. CSV values must not contain commas.

## Design

See [DESIGN.md](DESIGN.md) for the architecture, ordering and idempotency choices, failure behavior, production trade-offs, and AI-use disclosure.

## Expected sample behavior

- `TXN-2002` is processed after `TXN-2001` because sequence 1 precedes sequence 2 for account `ACC-201`.
- The second `TXN-2002` is reported as `DUPLICATE` and does not debit twice.
- `TXN-2004` is rejected because its amount is negative; the rest of the batch continues.
- `TXN-2005` fails once transiently, retries, and credits `ACC-203` exactly once.
- Final balances: `ACC-201=85`, `ACC-203=500`.

## Limits of this assessment implementation

State is in memory and lasts only for one process. A production version needs durable storage, atomic uniqueness constraints, concurrency control, a queue adapter, a recovery/replay policy, and metrics/alerts. The CSV adapter is intentionally small and replaceable.
