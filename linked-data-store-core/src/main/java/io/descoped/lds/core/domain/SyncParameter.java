package io.descoped.lds.core.domain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Whether a write is answered when it is done or as soon as it is taken: the query parameter {@code sync}. A write of
 * a document, of a part of it or of a link is answered when it is done, unless the request says {@code sync=false}.
 */
public final class SyncParameter {

    private SyncParameter() {
    }

    /**
     * Whether the write is to be done before it is answered.
     *
     * @param queryParameters the query parameters of the request
     * @return false only when a {@code sync} parameter is {@code false}, in any letter case, and the write is not to be
     * searchable when it is answered ({@code searchable=true})
     */
    public static boolean waits(Map<String, Deque<String>> queryParameters) {
        // a write that is to be searchable when it is answered is done first, whatever sync says
        return SearchWait.asked(queryParameters) || queryParameters.getOrDefault("sync", new ArrayDeque<>())
                .stream().noneMatch("false"::equalsIgnoreCase);
    }
}
