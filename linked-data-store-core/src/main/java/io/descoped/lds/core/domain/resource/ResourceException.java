package io.descoped.lds.core.domain.resource;

import java.io.Serial;

public class ResourceException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    ResourceException(String message) {
        super(message);
    }
}
