# Design Note

## Summary

This solution uses a small Java 17 batch processor with a replaceable CSV input adapter and an in-memory processing store. It validates records independently, de-duplicates requests and business transaction IDs, sorts valid events by account and sequence, retries a simulated transient error, and reports final outcomes and balances. This keeps the assessment focused on safe processing behavior rather than infrastructure setup.

## Architecture

`Main` reads CSV and converts rows into `Transaction` values. `TransactionProcessor` owns validation, duplicate detection, ordering, retry, balance updates, and result generation. `BatchReport` provides status counts and account balances. In production, the input adapter could be replaced with a queue or database reader, and the in-memory maps/sets with a transactional repository.

## Idempotency and consistency

The processor tracks both `requestId` and `transactionId`. A repeated request ID, a previously processed business transaction ID, or a repeated transaction ID in the same batch is returned as `DUPLICATE`. The balance change and processed-transaction marker happen together in the same synchronous processing section. This is adequate for a single-process demonstration but is not durable or safe across multiple instances.

For production, persist an idempotency key under a unique database constraint and update the transaction ledger and account balance in one database transaction. If a downstream side effect is involved, use a transactional outbox and make the consumer idempotent too. Never rely on an in-memory check-then-write across concurrent workers.

## Ordering

The chosen strategy is per-account sequence ordering. Valid records from the batch are sorted by sequence. The first sequence observed for each account establishes that batch's starting point; a missing sequence between observed records causes later events to be reported `PENDING`. A failed event blocks subsequent sequence numbers for that account in the current batch. Other accounts continue.

This policy handles the supplied out-of-order example while avoiding a global ordering bottleneck. A production service should store the last committed sequence per account and define what happens to a missing event (wait with a timeout, request replay, or route to manual review). Here the minimum sequence in a batch is the baseline, so the service cannot infer missing earlier history without a durable checkpoint.

## Retry and failure behavior

The sample's fault flag makes one transaction throw a transient exception on its first attempt. It is retried immediately up to the configured maximum of three attempts. The effect is applied only after the simulated failure point, so a retry cannot double-apply it. Exhausted retries return `FAILED`; operational logs identify retry attempts by transaction ID only. Invalid rows return `FAILED` before ordering and do not stop other accounts or records.

In production, use bounded exponential backoff with jitter, retry only classified transient errors, store attempts and next-attempt time durably, and move exhausted messages to a dead-letter queue with an operator recovery path. Persist an explicit `RETRY_PENDING` state before scheduling the retry.

## Status and observability

Each input receives a final result of `PROCESSED`, `DUPLICATE`, `FAILED`, or `PENDING`; retry attempts are logged as `RETRY_PENDING`. The CLI prints counts by status and balances. Logs avoid account/customer details and amounts. A production deployment should use structured logs with correlation IDs, metrics for throughput/duplicates/retries/failures/lag, and alerts for stalled sequences and retry exhaustion.

## Security and data handling

The solution validates mandatory IDs, positive amount, sequence, transaction type, and a three-letter currency code. It does not log customer identifiers, account IDs, or amounts. It has no credentials or external network access. Production work should also define authorization, input size limits, currency precision and scale, audit retention, and encryption requirements.

## Tests

`ProcessorSelfTest` exercises out-of-order processing, duplicate suppression, negative-amount validation with continued batch processing, retry without duplicate effect, and sequence-gap handling. It is dependency-free and can be run with assertions enabled using the commands in the README.

## AI assistance

AI assistance was used to draft the initial structure and documentation. The implementation was reviewed against the acceptance criteria and validated by compiling and running the executable checks and sample input. A candidate should be prepared to explain the processing boundary, the limitations of in-memory idempotency, and the batch-baseline ordering trade-off.

## Production hardening roadmap

1. Add a durable transaction ledger and idempotency table with unique constraints.
2. Store per-account sequence checkpoints and support replay/reconciliation for gaps.
3. Make processing transactional and define debit balance/overdraft rules.
4. Add queue visibility timeout, bounded retries, dead-letter handling, and recovery tooling.
5. Add concurrency tests, integration tests against the chosen database/queue, and fault-injection tests around commit boundaries.
6. Emit structured telemetry and operational dashboards without sensitive values.
