package com.example.transactions;

public record ProcessingResult(String transactionId, String requestId, Status status,
                              int attempts, String message) {
    public enum Status { PROCESSED, DUPLICATE, FAILED, RETRY_PENDING, PENDING }
}
