package io.descoped.lds.graphql.fetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.graphql.GraphQLContext;
import io.reactivex.rxjava3.core.Maybe;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PersistenceLinksFetcher implements DataFetcher<List<Map<String, Object>>> {

    private final String field;
    private final RxJsonPersistence persistence;
    private final Pattern pattern;
    private final String namespace;

    public PersistenceLinksFetcher(RxJsonPersistence persistence, String namespace, String field, String target) {
        this.field = Objects.requireNonNull(field);
        this.persistence = Objects.requireNonNull(persistence);
        this.pattern = Pattern.compile("/(?<type>" + Objects.requireNonNull(target) + ")/(?<id>.*)");
        this.namespace = Objects.requireNonNull(namespace);
    }

    @Override
    public List<Map<String, Object>> get(DataFetchingEnvironment environment) {
        Map<String, Object> source = environment.getSource();
        if (!(source.get(field) instanceof List<?> links)) {
            return null;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object item : links) {
            // An item that is not a text, a link that does not name the target, and a link to a document that does
            // not exist at the snapshot are left out.
            if (!(item instanceof String link)) {
                continue;
            }
            Matcher matcher = pattern.matcher(link);
            if (matcher.matches()) {
                // the entity type is the one the link names: the target can be the members of a union, (Cat|Dog)
                String type = matcher.group("type");
                String id = matcher.group("id");
                GraphQLContext context = environment.getGraphQlContext().get(GraphQLContext.class);
                JsonDocument document = readDocument(type, id, context.getSnapshot());
                if (document != null) {
                    Map<String, Object> asMap = document.toMap();
                    asMap.put("__graphql_internal_document_key", document.key());
                    results.add(asMap);
                }
            }
        }
        return results;
    }

    private JsonDocument readDocument(String type, String id, ZonedDateTime snapshot) {
        try (Transaction tx = persistence.createTransaction(true)) {
            Maybe<JsonDocument> jsonDocumentMaybe = persistence.readDocument(tx, snapshot, namespace, type, id);
            return jsonDocumentMaybe.blockingGet();
        }
    }
}
