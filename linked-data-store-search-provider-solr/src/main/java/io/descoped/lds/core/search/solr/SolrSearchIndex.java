package io.descoped.lds.core.search.solr;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.search.IndexEntry;
import io.descoped.lds.api.search.Language;
import io.descoped.lds.api.search.SearchHit;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchPage;
import io.descoped.lds.api.search.SearchQuery;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The search index in one Solr core ({@code search.index.url}): one Solr document for each version of each LDS
 * document, written or deleted, never changed. Its schema is LDS's own ({@link SolrSchema}) and is put in before the
 * core is first written or searched.
 * <p>
 * A search at a moment takes, of each document, the newest entry up to the moment, and only then tests it for the
 * words and for a deletion: tested first, the words would pick an older version of a document that does not have
 * them any more.
 */
public class SolrSearchIndex implements SearchIndex {

    private static final Logger LOG = LoggerFactory.getLogger(SolrSearchIndex.class);
    /* The default of search.index.commit-within, in milliseconds (decided by the owner, D7). */
    static final int COMMIT_WITHIN_MILLIS = 1000;
    /* The last place of a hit that Solr gives: start and rows are ints, and so is their sum. */
    private static final long MAX_PLACE = Integer.MAX_VALUE;
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String JSON = "application/json";
    /* Every text field: a word as it is written counts twice what a word with its letters folded does. */
    private static final String TEXT_FIELDS = Stream.concat(
                    SolrSchema.TEXT_FIELDS.values().stream().sorted().map(field -> field + "^2"),
                    SolrSchema.FOLDED_FIELDS.values().stream().sorted())
            .collect(Collectors.joining(" "));
    /* The user's words, read by edismax in every text field: a field or a query in them is read as words. */
    private static final String WORDS = "{!edismax qf=$ldsFields uf=-* q.op=OR sow=true v=$ldsText}";
    private static final String HIT_FIELDS = String.join(",", SolrSchema.NAMESPACE, SolrSchema.ENTITY,
            SolrSchema.DOCUMENT_ID, SolrSchema.VERSION_SECONDS, SolrSchema.VERSION_NANOS);
    private static final String TERM = "{!term f=";
    /* The newest version first. */
    private static final String NEWEST_FIRST = SolrSchema.VERSION_SECONDS + " desc,"
            + SolrSchema.VERSION_NANOS + " desc";

    private final HttpJdkSolrClient solrClient;
    private final int commitWithin;
    private final SolrSchema schema = new SolrSchema();
    private final AtomicReference<Completable> schemaPutIn = new AtomicReference<>();

    /**
     * The index of the Solr core at the address.
     *
     * @param urlString the address of the core, {@code http://solr:8983/solr/lds-index}
     */
    public SolrSearchIndex(String urlString) {
        this(urlString, COMMIT_WITHIN_MILLIS);
    }

    /**
     * The index of the Solr core at the address, which makes what it takes searchable within the time given.
     *
     * @param urlString    the address of the core, {@code http://solr:8983/solr/lds-index}
     * @param commitWithin within how many milliseconds an entry is searchable
     */
    public SolrSearchIndex(String urlString, int commitWithin) {
        if (commitWithin < 1) {
            throw new IllegalArgumentException("search.index.commit-within must be at least 1 ms: " + commitWithin);
        }
        this.solrClient = client(urlString);
        this.commitWithin = commitWithin;
    }

    /*
     * One client for updates and queries. HTTP/1.1: over HTTP/2 without TLS, the JDK's client sends every update with
     * a body as an empty request once Solr has closed an idle connection (after 120 s, Solr's
     * solr.jetty.http.idleTimeout) and a new one is opened, and Solr refuses each of them. SolrJ falls back to
     * HTTP/1.1 only when its first HEAD request fails, once. The timeouts replace SolrJ's defaults of 60 s to
     * connect and 10 minutes to answer.
     */
    private static HttpJdkSolrClient client(String url) {
        return new HttpJdkSolrClient.Builder(url)
                .useHttp1_1(true)
                .withConnectionTimeout(CONNECTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .withRequestTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    /*
     * An update is complete when Solr has answered it, and fails when Solr refuses it or cannot be reached: the
     * request is sent without blocking, and the Completable ends with Solr's answer.
     */
    @Override
    public Completable put(List<IndexEntry> entries) {
        if (entries.isEmpty()) {
            return Completable.complete();
        }
        return schemaPutIn().andThen(Completable.defer(() -> {
            UpdateRequest request = new UpdateRequest();
            request.add(entries.stream().map(SolrSearchIndex::solrDocument).toList());
            request.setCommitWithin(commitWithin);
            return send(request).doOnComplete(() -> LOG.debug("Solr took {} entries", entries.size()));
        }));
    }

    private static SolrInputDocument solrDocument(IndexEntry entry) {
        SolrInputDocument document = new SolrInputDocument();
        Instant version = entry.version().toInstant();
        String key = documentKey(entry.namespace(), entry.entity(), entry.id());
        // the version as it is written is unique for one version: seconds and nanoseconds
        document.addField(SolrSchema.ID, key + "!" + version.getEpochSecond() + "." + version.getNano());
        document.addField(SolrSchema.NAMESPACE, entry.namespace());
        document.addField(SolrSchema.ENTITY, entry.entity());
        document.addField(SolrSchema.DOCUMENT_ID, entry.id());
        document.addField(SolrSchema.DOCUMENT, key);
        document.addField(SolrSchema.VERSION_SECONDS, version.getEpochSecond());
        document.addField(SolrSchema.VERSION_NANOS, (long) version.getNano());
        document.addField(SolrSchema.DELETED, entry.deleted());
        for (Map.Entry<Language, List<String>> texts : entry.texts().entrySet()) {
            for (String text : texts.getValue()) {
                document.addField(SolrSchema.TEXT_FIELDS.get(texts.getKey()), text);
                document.addField(SolrSchema.FOLDED_FIELDS.get(texts.getKey()), text);
            }
        }
        return document;
    }

    /*
     * One value for every version of a document: SHA-256 of the namespace, the entity type and the id, each with its
     * length before it, so that no two documents have the same value whatever their names hold. The value is not
     * read back: a hit is made of the fields that hold the names.
     */
    private static String documentKey(String namespace, String entity, String id) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of(namespace, entity, id)) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /*
     * A soft commit that waits until a new searcher sees what Solr has taken: what is searched is made visible, and
     * nothing more is written to disk than commitWithin writes.
     */
    @Override
    public Completable refresh() {
        return Completable.defer(() -> {
            UpdateRequest request = new UpdateRequest();
            request.setAction(AbstractUpdateRequest.ACTION.COMMIT, true, true, true);
            return send(request);
        });
    }

    /*
     * A query of no rows on the core, which Solr answers when the core is loaded and can search. Solr's ping of a core
     * (/admin/ping) is deprecated in Solr 10.1, and its health check (/admin/info/health) is of the node, not the core.
     */
    @Override
    public Completable health() {
        return send(new QueryRequest(new SolrQuery("*:*").setRows(0)));
    }

    @Override
    public Single<SearchPage> search(SearchQuery query) {
        return schemaPutIn().andThen(Single.defer(() -> {
            QueryRequest request = new QueryRequest(parameters(query), SolrRequest.METHOD.POST);
            return answer(request).map(answer -> page(new QueryResponse(answer)));
        }));
    }

    /*
     * Every value from outside (the words, the namespace, the entity types) is a parameter of its own that the query
     * names with $, so none is read as query syntax.
     */
    private static ModifiableSolrParams parameters(SearchQuery query) {
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("ldsText", query.text());
        params.set("ldsFields", TEXT_FIELDS);
        params.set("ldsWords", WORDS);
        params.set("ldsDeleted", TERM + SolrSchema.DELETED + "}true");
        params.add("fq", TERM + SolrSchema.NAMESPACE + " v=$ldsNamespace}");
        params.set("ldsNamespace", query.namespace());
        if (!query.entities().isEmpty()) {
            List<String> entities = query.entities().stream().sorted().toList();
            StringBuilder anyOf = new StringBuilder("{!bool");
            for (int i = 0; i < entities.size(); i++) {
                anyOf.append(" should=$ldsEntity").append(i);
                params.set("ldsEntity" + i, TERM + SolrSchema.ENTITY + " v=$ldsEntityName" + i + "}");
                params.set("ldsEntityName" + i, entities.get(i));
            }
            params.add("fq", anyOf.append('}').toString());
        }
        // up to the moment, to the nanosecond: an earlier second, or the same second and no later nanosecond
        Instant snapshot = query.snapshot().toInstant();
        params.add("fq", "{!bool should=$ldsEarlier should=$ldsSameSecond}");
        params.set("ldsEarlier", SolrSchema.VERSION_SECONDS + ":[* TO " + (snapshot.getEpochSecond() - 1) + "]");
        params.set("ldsSameSecond", "{!bool must=$ldsSecond must=$ldsNanos}");
        params.set("ldsSecond", SolrSchema.VERSION_SECONDS + ":[" + snapshot.getEpochSecond() + " TO "
                + snapshot.getEpochSecond() + "]");
        params.set("ldsNanos", SolrSchema.VERSION_NANOS + ":[* TO " + snapshot.getNano() + "]");
        if (query.history()) {
            // every version that has the words and is not a deletion
            params.set("q", "{!query v=$ldsWords}");
            params.add("fq", "{!bool must_not=$ldsDeleted}");
        } else {
            // every version is a candidate, scored by the words, so the collapse sees them all and keeps the newest
            // of each document with its score; then only a newest one that has the words and is not a deletion
            params.set("q", "{!bool filter=$ldsAll should=$ldsWords}");
            params.set("ldsAll", "*:*");
            params.add("fq", "{!collapse field=" + SolrSchema.DOCUMENT + " sort='" + NEWEST_FIRST + "'}");
            params.set("ldsLive", "{!bool must=$ldsWords must_not=$ldsDeleted}");
            params.add("fq", "{!frange l=0 incl=false cache=false cost=200}query($ldsLive,0)");
        }
        params.set("sort", "score desc," + SolrSchema.DOCUMENT + " asc," + NEWEST_FIRST);
        params.set("fl", HIT_FIELDS);
        if (query.from() + query.size() > MAX_PLACE) {
            // past every place Solr can give: no hits, and the number of hits in all
            params.set("start", 0);
            params.set("rows", 0);
        } else {
            params.set("start", (int) query.from());
            params.set("rows", (int) query.size());
        }
        return params;
    }

    private static SearchPage page(QueryResponse response) {
        SolrDocumentList documents = response.getResults();
        List<SearchHit> hits = new ArrayList<>(documents.size());
        for (SolrDocument document : documents) {
            Instant version = Instant.ofEpochSecond((Long) document.getFirstValue(SolrSchema.VERSION_SECONDS),
                    (Long) document.getFirstValue(SolrSchema.VERSION_NANOS));
            hits.add(new SearchHit(new DocumentKey((String) document.getFirstValue(SolrSchema.NAMESPACE),
                    (String) document.getFirstValue(SolrSchema.ENTITY),
                    (String) document.getFirstValue(SolrSchema.DOCUMENT_ID),
                    ZonedDateTime.ofInstant(version, ZoneOffset.UTC))));
        }
        return new SearchPage(documents.getNumFound(), hits);
    }

    /*
     * The schema is put in once, before the first write or search, and again after a failure: Solr may not be up when
     * LDS starts. Two LDS that put it in at once both end with it: the one that adds second is refused, reads the
     * core again and replaces what the other added with the same.
     */
    private Completable schemaPutIn() {
        Completable putIn = schemaPutIn.get();
        if (putIn != null) {
            return putIn;
        }
        AtomicReference<Completable> self = new AtomicReference<>();
        Completable attempt = Completable.defer(this::putSchemaIn).retry(2)
                .doOnError(_ -> schemaPutIn.compareAndSet(self.get(), null))
                .cache();
        self.set(attempt);
        return schemaPutIn.compareAndSet(null, attempt) ? attempt : schemaPutIn.get();
    }

    private Completable putSchemaIn() {
        return answer(new GenericSolrRequest(SolrRequest.METHOD.GET, "/config/overlay"))
                .flatMapCompletable(overlay -> {
                    Object mark = SolrSchema.get(SolrSchema.get(SolrSchema.get(overlay, "overlay"), "userProps"),
                            SolrSchema.MARK);
                    if (schema.markOfThisSchema().equals(mark)) {
                        return Completable.complete();
                    }
                    // the default chain of updates makes a field of every name it does not know, and refuses every
                    // update once the core has 1000 fields; off, Solr refuses a field it does not know
                    return post("/config", "{\"set-user-property\": {\"update.autoCreateFields\": \"false\"}}")
                            .andThen(Single.zip(names("/schema/fieldtypes", "fieldTypes"),
                                    names("/schema/fields", "fields"),
                                    (types, fields) -> {
                                        if (schema.replaces(types, fields)) {
                                            LOG.warn("The schema of the search index is changed: what was "
                                                    + "indexed before is searched as it was indexed until the index "
                                                    + "is rebuilt (search.index.rebuild)");
                                        }
                                        return schema.changes(types, fields);
                                    }))
                            .flatMapCompletable(changes -> post("/schema", changes))
                            .andThen(post("/config", "{\"set-user-property\": {\"" + SolrSchema.MARK + "\": \""
                                    + schema.markOfThisSchema() + "\"}}"))
                            .doOnComplete(() -> LOG.info("The schema of the search index is put in ({})",
                                    schema.markOfThisSchema()));
                });
    }

    private Single<Set<String>> names(String path, String list) {
        return answer(new GenericSolrRequest(SolrRequest.METHOD.GET, path)).map(answer -> {
            Set<String> names = new HashSet<>();
            if (SolrSchema.get(answer, list) instanceof List<?> elements) {
                for (Object element : elements) {
                    if (SolrSchema.get(element, "name") instanceof String name) {
                        names.add(name);
                    }
                }
            }
            return names;
        });
    }

    private Completable post(String path, String json) {
        return send(new GenericSolrRequest(SolrRequest.METHOD.POST, path)
                .withContent(json.getBytes(StandardCharsets.UTF_8), JSON));
    }

    /*
     * Sends a request and completes with Solr's answer: an error that Solr answers, or that the client meets on the
     * way, is the error, as SolrJ gives it (RemoteSolrException, SolrServerException, ...).
     */
    private Completable send(SolrRequest<?> request) {
        return answer(request).ignoreElement();
    }

    private Single<NamedList<Object>> answer(SolrRequest<?> request) {
        return Single.defer(() -> {
            CompletableFuture<NamedList<Object>> answer = solrClient.requestAsync(request);
            return Single.fromCompletionStage(answer);
        }).onErrorResumeNext(error -> Single.error(error instanceof CompletionException && error.getCause() != null
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
}
