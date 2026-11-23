package io.descoped.lds.core.search.solr;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.flattened.FlattenedDocument;
import io.descoped.lds.api.persistence.flattened.FlattenedDocumentLeafNode;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonToFlattenedDocument;
import io.descoped.lds.api.persistence.streaming.FragmentType;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchResponse;
import io.descoped.lds.api.search.SearchResult;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.ConcurrentUpdateBaseSolrClient;
import org.apache.solr.client.solrj.impl.ConcurrentUpdateJdkSolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * A Solr implementation of {@link SearchIndex} that will execute queries and updates to Solr server via HTTP.
 * The environment property @{code search.index.url} must be provided.
 */
public class SolrSearchIndex implements SearchIndex {

    private final SolrClient solrUpdateClient;
    private final SolrClient solrQueryClient;
    private final FailedUpdates failedUpdates;
    private static final Logger LOG = LoggerFactory.getLogger(SolrSearchIndex.class);
    private static final String DEFAULT_SEARCH_FIELD = "searchfield";
    private static final String ENTITY_FIELD = "entity";
    private static final int COMMIT_WITHIN_MILLIS = 5000;
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    public SolrSearchIndex(String urlString) {
        this(client(urlString), new FailedUpdates());
    }

    /*
     * Updates are queued and sent by threads of the update client, over the connection of the query client. A
     * failed update is reported to the update client's error handler, not to the caller, who has already gone on.
     */
    private SolrSearchIndex(HttpJdkSolrClient solrQueryClient, FailedUpdates failedUpdates) {
        this(new ConcurrentUpdateJdkSolrClient.Builder(solrQueryClient.getBaseURL(), solrQueryClient)
                .withErrorHandler(failedUpdates)
                .build(), solrQueryClient, failedUpdates);
    }

    SolrSearchIndex(SolrClient solrUpdateClient, SolrClient solrQueryClient, FailedUpdates failedUpdates) {
        LOG.info("Initializing SolrSearchIndex");
        this.solrUpdateClient = solrUpdateClient;
        this.solrQueryClient = solrQueryClient;
        this.failedUpdates = failedUpdates;
    }

    /*
     * HTTP/1.1: over HTTP/2 without TLS, the JDK's client sends every update with a body as an empty request once
     * Solr has closed an idle connection (after 120 s, Solr's solr.jetty.http.idleTimeout) and a new one is opened,
     * and Solr refuses each of them. SolrJ falls back to HTTP/1.1 only when its first HEAD request fails, once.
     * The timeouts replace SolrJ's defaults of 60 s to connect and 10 minutes to answer.
     */
    private static HttpJdkSolrClient client(String url) {
        return new HttpJdkSolrClient.Builder(url)
                .useHttp1_1(true)
                .withConnectionTimeout(CONNECTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .withRequestTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * The number of documents whose update or delete did not reach Solr since the index was made, a request counted
     * as one when Solr's client does not say which documents it held.
     */
    long failedUpdates() {
        return failedUpdates.count();
    }

    @Override
    public Completable createOrOverwrite(JsonDocument jsonDocument) {
        return createOrOverwrite(Arrays.asList(jsonDocument));
    }

    @Override
    public Completable createOrOverwrite(Collection<JsonDocument> jsonDocuments) {
        return Completable.fromAction(() -> {
            List<SolrInputDocument> updateDocs = new ArrayList<>();
            for (JsonDocument jsonDocument : jsonDocuments) {
                final SolrInputDocument doc = new SolrInputDocument();
                DocumentKey key = jsonDocument.key();
                JsonToFlattenedDocument converter = new JsonToFlattenedDocument(key.namespace(), key.entity(), key.id(),
                        key.timestamp(), jsonDocument.jackson(), Integer.MAX_VALUE);
                addFields(converter.toDocument(), doc);
                doc.addField(CommonParams.ID, getIdString(jsonDocument.key()));
                doc.addField(ENTITY_FIELD, jsonDocument.key().entity());
                updateDocs.add(doc);
            }
            this.solrUpdateClient.add(updateDocs, COMMIT_WITHIN_MILLIS);
            LOG.info("Queued {} documents for the index", jsonDocuments.size());
        });
    }

    @Override
    public Completable delete(JsonDocument jsonDocument) {
        return Completable.fromAction(() -> {
            final String idString = getIdString(jsonDocument.key());
            this.solrUpdateClient.deleteById(idString, COMMIT_WITHIN_MILLIS);
            LOG.info("Finished deleting : {}", idString);
        });
    }

    @Override
    public Completable deleteAll() {
        return Completable.fromAction(() -> {
            this.solrUpdateClient.deleteByQuery("*:*");
            this.solrUpdateClient.commit();
        });
    }

    @Override
    public Single<SearchResponse> search(String text, Set<String> typeFilters, long from, long size) {
        try {
            SolrQuery query = new SolrQuery().setStart((int) from)
                    .setRows((int) size);
            query.set(CommonParams.Q, text);
            query.set(CommonParams.DF, DEFAULT_SEARCH_FIELD);
            if (typeFilters != null && !typeFilters.isEmpty()) {
                query.addFilterQuery(entityFilter(typeFilters));
            }
            LOG.info("Executing query: {}", query);
            SolrDocumentList response = this.solrQueryClient.query(query).getResults();
            LOG.info("Number of hits: {}", response.getNumFound());
            List<SearchResult> results = response.stream()
                    .map(result -> new SearchResult(fromIdString(result.get("id").toString())))
                    .collect(Collectors.toList());
            return Single.just(new SearchResponse(response.getNumFound(), results, from, size));
        } catch (Exception e) {
            LOG.error("An error ocurred", e);
            throw new RuntimeException(e);
        }
    }

    /*
     * The entities a search is narrowed to, as a filter of its own (fq) beside the user's text (q), so the text's
     * operators cannot loosen it. Each name is escaped as a term. The core is schemaless, which has made the field
     * analysed text, so the names are matched through the standard parser and its analysis, not as raw terms.
     */
    private static String entityFilter(Set<String> typeFilters) {
        return ENTITY_FIELD + ":(" + typeFilters.stream().sorted().map(ClientUtils::escapeQueryChars)
                .collect(Collectors.joining(" OR ")) + ")";
    }

    /**
     * Counts and logs the documents whose update did not reach Solr, or the request, as one, when the update client
     * does not say which documents it held. The update client calls it from its own threads, after the write that
     * queued the update has completed.
     */
    static final class FailedUpdates implements ConcurrentUpdateBaseSolrClient.UpdateErrorHandler {

        private final AtomicLong count = new AtomicLong();

        @Override
        public void onError(Throwable error, List<String> failedIds, String collection) {
            count.addAndGet(failedIds.size());
            LOG.error("{} documents did not reach the search index: {}", failedIds.size(), failedIds, error);
        }

        @Override
        public void onError(Throwable error) {
            count.incrementAndGet();
            LOG.error("An update did not reach the search index", error);
        }

        long count() {
            return count.get();
        }
    }

    private void addFields(FlattenedDocument node, SolrInputDocument doc) {
        for (Map.Entry<String, FlattenedDocumentLeafNode> entry : node.leafNodesByPath().entrySet()) {
            if (entry.getValue().type() == FragmentType.STRING && entry.getKey().contains("languageText")) {
                doc.addField(entry.getKey(), entry.getValue().value());
                doc.addField(DEFAULT_SEARCH_FIELD, entry.getValue().value());
            }
        }
    }

    private String getIdString(DocumentKey key) {
        return String.format("%s/%s/%s/%s",
                key.namespace(), key.entity(), key.id(), key.timestamp()
        );
    }


    private DocumentKey fromIdString(String id) {
        String[] splitted = id.split("/");
        ZonedDateTime version = ZonedDateTime.parse(splitted[3] + "/" + splitted[4], DateTimeFormatter.ISO_ZONED_DATE_TIME);
        return new DocumentKey(splitted[0],
                splitted[1],
                splitted[2], version);
    }

}
