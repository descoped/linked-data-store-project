package io.descoped.lds.api.search;

import io.descoped.lds.api.persistence.DocumentKey;

public class SearchResult {

    private final DocumentKey documentKey;

    public SearchResult(DocumentKey documentKey) {
        this.documentKey = documentKey;
    }

    public DocumentKey getDocumentKey() {
        return documentKey;
    }
}
