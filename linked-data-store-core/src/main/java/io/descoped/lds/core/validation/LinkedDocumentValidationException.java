package io.descoped.lds.core.validation;

import java.io.Serial;

public class LinkedDocumentValidationException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    LinkedDocumentValidationException(String message) {
        super(message);
    }

}
