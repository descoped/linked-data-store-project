package io.descoped.lds.core.domain;

/**
 * A request body LDS does not read, with the status to answer and a text that says what to send instead.
 */
public class BodyException extends RuntimeException {

    private final int status;

    BodyException(int status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * The status of the answer: 400 for a body that cannot be read, 415 for a content type LDS does not read.
     *
     * @return the HTTP status
     */
    public int status() {
        return status;
    }
}
