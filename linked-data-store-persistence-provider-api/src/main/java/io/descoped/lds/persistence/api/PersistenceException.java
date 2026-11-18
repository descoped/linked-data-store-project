package io.descoped.lds.persistence.api;

import java.io.Serial;

/**
 * A persistence operation failed in the provider or in the engine behind it. Wrong arguments are reported with
 * {@link IllegalArgumentException} and misuse of a transaction with {@link IllegalStateException} instead.
 */
public class PersistenceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed
     */
    public PersistenceException(String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message what failed
     * @param cause   the engine's own failure
     */
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
