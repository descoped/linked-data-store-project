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
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.UpdateRequest;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A Solr implementation of {@link SearchIndex} that will execute queries and updates to Solr server via HTTP.
 * The environment property @{code search.index.url} must be provided.
 */
public class SolrSearchIndex implements SearchIndex {

    private final HttpJdkSolrClient solrClient;
    private static final Logger LOG = LoggerFactory.getLogger(SolrSearchIndex.class);
    private static final String DEFAULT_SEARCH_FIELD = "searchfield";
    private static final String ENTITY_FIELD = "entity";
    private static final int COMMIT_WITHIN_MILLIS = 5000;
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /**
     * The index of the Solr core at the address.
     *
     * @param urlString the address of the core, {@code http://solr:8983/solr/lds-index}
     */
    public SolrSearchIndex(String urlString) {
        this(client(urlString));
    }

    SolrSearchIndex(HttpJdkSolrClient solrClient) {
        LOG.info("Initializing SolrSearchIndex");
        this.solrClient = solrClient;
    }

    /*
     * One client for updates and queries. HTTP/1.1: over HTTP/2 without TLS, the JDK's client sends every update with a body as an empty request once
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

    @Override
    public Completable createOrOverwrite(JsonDocument jsonDocument) {
        return createOrOverwrite(Arrays.asList(jsonDocument));
    }

    /*
     * An update is complete when Solr has answered it, and fails when Solr refuses it or cannot be reached: the
     * request is sent without blocking, and the Completable ends with Solr's answer.
     */
    @Override
    public Completable createOrOverwrite(Collection<JsonDocument> jsonDocuments) {
        return Completable.defer(() -> {
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
            UpdateRequest request = new UpdateRequest();
            request.add(updateDocs);
            request.setCommitWithin(COMMIT_WITHIN_MILLIS);
            return send(request).doOnComplete(() -> LOG.debug("Solr took {} documents", updateDocs.size()));
        });
    }

    @Override
    public Completable delete(JsonDocument jsonDocument) {
        return Completable.defer(() -> {
            String idString = getIdString(jsonDocument.key());
            UpdateRequest request = new UpdateRequest().deleteById(idString);
            request.setCommitWithin(COMMIT_WITHIN_MILLIS);
            return send(request).doOnComplete(() -> LOG.debug("Solr deleted {}", idString));
        });
    }

    @Override
    public Completable deleteAll() {
        return Completable.defer(() -> {
            UpdateRequest request = new UpdateRequest().deleteByQuery("*:*");
            request.setAction(AbstractUpdateRequest.ACTION.COMMIT, true, true);
            return send(request);
        });
    }

    /*
     * Sends a request and completes with Solr's answer: an error that Solr answers, or that the client meets on the
     * way, is the error of the Completable, as SolrJ gives it (RemoteSolrException, SolrServerException, ...).
     */
    private Completable send(UpdateRequest request) {
        return Completable.fromCompletionStage(solrClient.requestAsync(request))
                .onErrorResumeNext(error -> Completable.error(error instanceof CompletionException && error.getCause() != null
                        ? unwrapped(error.getCause()) : error));
    }

    /*
     * The client wraps a SolrServerException in a RuntimeException when it reads the answer.
     */
    private static Throwable unwrapped(Throwable error) {
        if (error.getClass() == RuntimeException.class && error.getCause() instanceof SolrServerException cause) {
            return cause;
        }
        return error;
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
            SolrDocumentList response = this.solrClient.query(query).getResults();
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
