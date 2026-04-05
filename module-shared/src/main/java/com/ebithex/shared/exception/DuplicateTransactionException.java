package com.ebithex.shared.exception;

public class DuplicateTransactionException extends EbithexException {

    public DuplicateTransactionException(String message) {
        super(ErrorCode.DUPLICATE_TRANSACTION, message);
    }
}
