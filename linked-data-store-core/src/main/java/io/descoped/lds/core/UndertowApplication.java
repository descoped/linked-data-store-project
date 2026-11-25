package io.descoped.lds.core;

import io.descoped.lds.core.utils.Jitter;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.api.persistence.Limits;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.core.controller.CORSHandler;
import io.descoped.lds.core.health.Readiness;
import io.descoped.lds.core.health.ReadinessHandler;
import io.descoped.lds.core.lifecycle.Lifecycle;
import io.descoped.lds.core.lifecycle.Lifecycles;
import io.descoped.lds.core.controller.NamespaceController;
import io.descoped.lds.core.domain.DocumentLocks;
import io.descoped.lds.core.domain.SearchWait;
import io.descoped.lds.core.persistence.PersistenceConfigurator;
import io.descoped.lds.core.persistence.StoreReads;
import io.descoped.lds.core.saga.SagaExecutionCoordinator;
import io.descoped.lds.core.saga.SagaRecoveryTrigger;
import io.descoped.lds.core.saga.SagaRepository;
import io.descoped.lds.core.saga.SagasObserver;
import io.descoped.lds.core.schema.JsonSchema;
import io.descoped.lds.core.schema.JsonSchemaBuilder;
import io.descoped.lds.core.search.IndexQueueConfigurator;
import io.descoped.lds.core.search.IndexEntries;
import io.descoped.lds.core.search.IndexQueueWorker;
import io.descoped.lds.core.search.IndexRebuild;
import io.descoped.lds.core.search.SearchIndexConfigurator;
import io.descoped.lds.core.specification.JsonSchemaBasedSpecification;
import io.descoped.lds.core.specification.SpecificationJsonSchemaBuilder;
import io.descoped.lds.core.txlog.TxlogRawdataPool;
import io.descoped.lds.graphql.GraphqlHttpHandler;
import io.descoped.lds.graphql.jsonschema.GraphQLToJsonConverter;
import io.descoped.lds.graphql.directives.DomainDirective;
import io.descoped.lds.graphql.schemas.GraphQLSchemaBuilder;
import io.descoped.rawdata.api.RawdataClient;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.util.StatusCodes;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.Clock;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class UndertowApplication implements Lifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(UndertowApplication.class);
    private final RxJsonPersistence persistence;
    private final DocumentLocks documentLocks;
    private final Specification specification;
    private final Undertow server;
    /** In front of every handler but the CORS handler: counts the requests in flight, and refuses new ones when the application stops. */
    private final GracefulShutdownHandler requests;
    private final String host;
    private final int port;
    private final SagaExecutionCoordinator sec;
    private final SagaRepository sagaRepository;
    private final SagasObserver sagasObserver;
    private final SagaLogPool sagaLogPool;
    private final SelectableThreadPoolExecutor sagaThreadPool;
    private final SagaRecoveryTrigger sagaRecoveryTrigger;
    private final TxlogRawdataPool txlogRawdataPool;
    private final Readiness readiness;
    // the threads the store is read on
    private final StoreReads storeReads;
    // the subsystems, started and stopped with the application
    private final Lifecycles lifecycles;

    /**
     * The parts of the write path the application runs and stops.
     *
     * @param sec              runs the sagas
     * @param repository       the sagas there are
     * @param observer         sees each saga complete or fail
     * @param recoveryTrigger  starts the recovery of sagas that did not complete
     * @param logPool          the saga logs
     * @param threadPool       the threads the sagas run on
     * @param txlogRawdataPool the transaction log
     */
    record SagaParts(SagaExecutionCoordinator sec, SagaRepository repository, SagasObserver observer,
                     SagaRecoveryTrigger recoveryTrigger, SagaLogPool logPool, SelectableThreadPoolExecutor threadPool,
                     TxlogRawdataPool txlogRawdataPool) {
    }

    /**
     * Search: the index, the queue of the updates it has not taken, the worker that sends them, and the rebuild of the
     * index from storage when the start asks for it.
     *
     * @param index   the search index
     * @param queue   the index queue
     * @param worker  the worker
     * @param rebuild the rebuild, or null
     */
    record Indexing(SearchIndex index, IndexQueue queue, IndexQueueWorker worker, IndexRebuild rebuild) {
    }

    /* Undertow's defaults, set here so that what depends on them follows: an I/O thread for each processor, at least
     * 2, and 8 request threads for each. */
    static final int IO_THREADS = Math.max(Runtime.getRuntime().availableProcessors(), 2);
    static final int REQUEST_THREADS = IO_THREADS * 8;

    /* How long the worker of the index queue waits for updates when there were none. */
    static final Duration INDEX_QUEUE_POLL = Duration.ofMillis(200);

    /**
     * What the application is made of, made before it.
     *
     * @param specification       the model
     * @param persistence         the store
     * @param storeReads          the threads the store is read on
     * @param sagas               the parts of the write path
     * @param namespaceController the handler of the documents
     * @param indexing            search, or null
     * @param lifecycles          the subsystems made so far, to which the application adds its own
     */
    record Parts(Specification specification, RxJsonPersistence persistence, StoreReads storeReads, SagaParts sagas,
                 NamespaceController namespaceController, Indexing indexing, Lifecycles lifecycles) {
    }

    UndertowApplication(Parts parts, int port, DynamicConfiguration configuration) {
        SagaParts sagas = parts.sagas();
        NamespaceController namespaceController = parts.namespaceController();
        Indexing indexing = parts.indexing();
        this.specification = parts.specification();
        this.storeReads = parts.storeReads();
        this.lifecycles = parts.lifecycles();
        this.documentLocks = namespaceController.documentLocks();
        this.sagaRecoveryTrigger = sagas.recoveryTrigger();
        this.host = configuration.find("http.host").orElse(null);
        this.port = port;
        this.persistence = parts.persistence();
        this.sec = sagas.sec();
        this.sagaRepository = sagas.repository();
        this.sagasObserver = sagas.observer();
        this.sagaLogPool = sagas.logPool();
        this.sagaThreadPool = sagas.threadPool();
        this.txlogRawdataPool = sagas.txlogRawdataPool();
        SearchIndex searchIndex = indexing == null ? null : indexing.index();

        LOG.info("Initializing Http handlers ...");

        String namespace = configuration.find("namespace.default").orElse(null);
        boolean enableRequestDump = configuration.getBoolean("http.request.dump", false);
        boolean graphqlEnabled = configuration.getBoolean("graphql.enabled", false);
        String pathPrefix = configuration.find("http.prefix").orElse(null);

        Optional<String> graphQLSchemaPath = configuration.find("graphql.schema")
                .map(path -> path.isEmpty() ? null : path);

        PathHandler pathHandler = Handlers.path();
        if (graphqlEnabled && graphQLSchemaPath.isPresent()) {

            LOG.info("Initializing GraphQL Web API ...");

            GraphQLSchemaBuilder schemaBuilder = new GraphQLSchemaBuilder(namespace, persistence, searchIndex, storeReads);
            TypeDefinitionRegistry definitionRegistry;
            File graphQLFile = new File(graphQLSchemaPath.get());
            definitionRegistry = parseSchemaFile(graphQLFile);

            GraphQLSchema schema = schemaBuilder.getGraphQL(GraphQLSchemaBuilder.parseSchema(definitionRegistry));

            GraphQL graphQL = GraphQL.newGraphQL(schema).build();

            pathHandler.addExactPath("/graphiql", Handlers.resource(new ClassPathResourceManager(
                    Thread.currentThread().getContextClassLoader(), "io/descoped/lds/graphql/graphiql"
            )).setDirectoryListingEnabled(false).addWelcomeFiles("graphiql.html"));

            GraphqlHttpHandler graphqlHttpHandler = new GraphqlHttpHandler(graphQL);
            pathHandler.addExactPath("/graphql", graphqlHttpHandler);
        }

        LOG.info("Initializing health handlers ...");

        // alive and ping: the process answers; whether LDS can take writes is the readiness, which asks every part
        ResponseCodeHandler answers = new ResponseCodeHandler(StatusCodes.OK);
        pathHandler.addExactPath(ReadinessHandler.ALIVE_PATH, answers);
        pathHandler.addExactPath(ReadinessHandler.PING_PATH, answers);
        this.readiness = new Readiness();
        lifecycles.add(Lifecycle.of("Readiness checks", _ -> readiness.close()));
        readiness.persistence(persistence, storeReads, namespace, new TreeSet<>(specification.getManagedDomains()).first())
                .sagaLog(sagaLogPool)
                .transactionLog(txlogRawdataPool);
        if (indexing != null) {
            readiness.search(indexing.index()).indexQueue(indexing.queue(), Clock.systemUTC());
        }
        pathHandler.addExactPath(ReadinessHandler.PATH, new ReadinessHandler(readiness));
        pathHandler.addPrefixPath("/", namespaceController);

        HttpHandler httpHandler;
        if (enableRequestDump) {
            LOG.info("Initializing request-dump ...");
            httpHandler = Handlers.requestDump(pathHandler);
        } else {
            httpHandler = pathHandler;
        }

        if (pathPrefix != null && !pathPrefix.isEmpty()) {
            LOG.info("Using http prefix: {}", pathPrefix);
            httpHandler = Handlers.path(ResponseCodeHandler.HANDLE_404).addPrefixPath(pathPrefix, httpHandler);
        }

        LOG.info("Initializing CORS-handler ...");

        // an entry of a list is what is between two commas, without the blanks around it
        List<Pattern> corsAllowOrigin = Stream.of(configuration.find("http.cors.allow.origin").orElse(null)
                .split(",")).map(String::trim).map(Pattern::compile).toList();
        Set<String> corsAllowMethods = Stream.of(configuration.find("http.cors.allow.methods").orElse(null)
                .split(",")).map(String::trim).collect(Collectors.toUnmodifiableSet());
        Set<String> corsAllowHeaders = Stream.of(configuration.find("http.cors.allow.header").orElse(null)
                .split(",")).map(String::trim).collect(Collectors.toUnmodifiableSet());
        boolean corsAllowCredentials = configuration.getBoolean("http.cors.allow.credentials", false);
        int corsMaxAge = configuration.getInt("http.cors.allow.max-age");

        // inside the CORS handler, so that its 503 has the CORS headers and a browser can read the answer
        this.requests = new GracefulShutdownHandler(httpHandler);
        CORSHandler corsHandler = new CORSHandler(requests, requests, new CORSHandler.Policy(corsAllowOrigin,
                corsAllowCredentials, StatusCodes.NO_CONTENT, corsMaxAge, corsAllowMethods, corsAllowHeaders
        ));

        LOG.info("Initializing Undertow ...");

        this.server = Undertow.builder()
                .addHttpListener(port, host)
                .setIoThreads(IO_THREADS)
                .setWorkerThreads(REQUEST_THREADS)
                // the stop has already waited for the requests (stop(timeout)); a connection still open (a client
                // that sends its body slowly or not at all, or keeps the connection) would otherwise hold the stop
                // until Undertow's read timeout of ten minutes
                .setServerOption(UndertowOptions.SHUTDOWN_TIMEOUT, 1000)
                .setHandler(corsHandler)
                .build();
        lifecycles.add(Lifecycle.of("HTTP server", server::start, timeout -> {
            requests.shutdown(); // every request from here on is answered 503 Service Unavailable
            if (!requests.awaitShutdown(timeout.toMillis())) {
                LOG.warn("Requests were still being handled after {}. They lose their connection when the HTTP "
                        + "server stops.", timeout);
            }
            server.stop();
        }));
    }

    private static TypeDefinitionRegistry parseSchemaFile(File graphQLFile) {
        return parseSchemaFile(graphQLFile.getPath(), ClassLoader.getSystemClassLoader());
    }

    /*
     * The GraphQL schema at the path: the resource of the class loader when it has one, in a folder or in a jar, and
     * the file otherwise.
     */
    static TypeDefinitionRegistry parseSchemaFile(String path, ClassLoader classLoader) {
        URL resource = classLoader.getResource(path);
        if (resource == null) {
            return new SchemaParser().parse(new File(path));
        }
        try (Reader reader = new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8)) {
            return new SchemaParser().parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("The GraphQL schema " + path + " cannot be read from " + resource, e);
        }
    }

    /*
     * The files and folders of JSON Schemas that specification.schema names, separated by commas: each without the
     * spaces around it.
     */
    static String[] specificationSchemaFiles(String configured) {
        return "".equals(configured) ? new String[0] : Stream.of(configured.split(",")).map(String::trim)
                .toArray(String[]::new);
    }

    /** The class path resource that holds the defaults of the configuration of core */
    public static final String DEFAULT_CONFIGURATION_RESOURCE_PATH = "application-defaults.properties";

    public static UndertowApplication initializeUndertowApplication(DynamicConfiguration configuration) {
        int port = configuration.getInt("http.port");
        return initializeUndertowApplication(configuration, port);
    }

    /**
     * Makes the application and its subsystems; none is started. When a subsystem cannot be made, those made before
     * it are stopped, and the exception is thrown.
     *
     * @param configuration the configuration
     * @param port          the port of the HTTP server
     * @return the application
     */
    public static UndertowApplication initializeUndertowApplication(DynamicConfiguration configuration, int port) {
        LOG.info("Initializing Linked Data Store (LDS) server ...");
        Lifecycles lifecycles = new Lifecycles();
        boolean made = false;
        try {
            UndertowApplication application = initialize(configuration, port, lifecycles);
            made = true;
            return application;
        } finally {
            if (!made) {
                LOG.error("LDS could not be made; what was made is stopped");
                lifecycles.stop(STOP_TIMEOUT);
            }
        }
    }

    private static UndertowApplication initialize(DynamicConfiguration configuration, int port, Lifecycles lifecycles) {

        LOG.info("Initializing specification ...");

        JsonSchemaBasedSpecification specification;
        Set<String> notSearched = Set.of();

        Optional<String> graphQLSchemaPath = configuration.find("graphql.schema")
                .map(path -> path.isEmpty() ? null : path);

        if (graphQLSchemaPath.isPresent()) {
            File graphQLFile = new File(graphQLSchemaPath.get());

            TypeDefinitionRegistry definitionRegistry = parseSchemaFile(graphQLFile);

            GraphQLSchema schema = GraphQLSchemaBuilder.parseSchema(definitionRegistry);
            notSearched = DomainDirective.notSearched(schema);
            GraphQLToJsonConverter graphQLToJsonConverter = new GraphQLToJsonConverter(schema);
            SequencedMap<String, ObjectNode> jsonMap = graphQLToJsonConverter.createSpecification(schema);

            LOG.info("Creating specification using GraphQL schema: {}", graphQLSchemaPath.get());
            specification = createJsonSpecification(jsonMap);

        } else {
            String schemaConfigStr = configuration.get("specification.schema");
            String[] specificationSchema = specificationSchemaFiles(schemaConfigStr);
            LOG.info("Creating specification using json-schema: {}", schemaConfigStr);
            specification = JsonSchemaBasedSpecification.create(specificationSchema);
        }
        // Checked here, before the persistence provider is started and given time to come up: a model with no entity
        // type is a fault of the configuration, and waiting does not change it.
        if (specification == null || specification.getManagedDomains().isEmpty()) {
            throw new IllegalStateException(graphQLSchemaPath
                    .map(path -> "The model " + path + " has no entity type: a GraphQL schema needs a type with @domain")
                    .orElseGet(() -> configuration.get("specification.schema").isEmpty()
                            ? "No model is given: set graphql.schema or specification.schema"
                            : "The model " + configuration.get("specification.schema") + " has no entity type"));
        }
        checkNames(configuration.find("namespace.default").orElse(""), specification.getManagedDomains());

        LOG.info("Initializing primary persistence ...");

        RxJsonPersistence persistence = PersistenceConfigurator.configurePersistence(configuration, specification);
        lifecycles.add(Lifecycle.of("Store", _ -> persistence.close()));
        StoreReads storeReads = new StoreReads();
        lifecycles.add(Lifecycle.of("Store reads", storeReads::close));

        LOG.info("Initializing saga-log pool ...");

        SagaLogPool sagaLogPool = configureSagaLogProvider(configuration);
        lifecycles.add(Lifecycle.of("Saga log pool", _ -> sagaLogPool.shutdown()));

        SagaRepository.Builder sagaRepositoryBuilder = new SagaRepository.Builder()
                .specification(specification)
                .persistence(persistence);

        LOG.info("Initializing search-index ...");

        Indexing indexing = indexing(configuration, specification, notSearched, persistence, sagaRepositoryBuilder,
                lifecycles);

        LOG.info("Initializing transaction-log ...");

        RawdataClient txLogClient = configureTxLogRawdataClient(configuration);
        lifecycles.add(Lifecycle.of("Transaction log", _ -> txLogClient.close()));
        boolean splitSources = configuration.getBoolean("txlog.split.sources", false);
        String defaultSource = configuration.find("txlog.default-source").filter(s -> !s.isBlank()).orElse("default");
        String txLogTopicPrefix = configuration.find("txlog.rawdata.topic-prefix").map(String::trim).orElse("");
        // The provider discard keeps nothing: its client is not exported by rawdata-client-api, so the name tells.
        boolean keepsMessages = !"discard".equals(configuration.find("txlog.rawdata.provider").orElse(null));
        TxlogRawdataPool txlogRawdataPool = new TxlogRawdataPool(txLogClient, splitSources, defaultSource, txLogTopicPrefix,
                keepsMessages);
        sagaRepositoryBuilder.txLogRawdataPool(txlogRawdataPool);

        LOG.info("Initializing saga repository ...");

        SagaRepository sagaRepository = sagaRepositoryBuilder.build();

        LOG.info("Initializing saga observer ...");

        final SagasObserver sagasObserver = new SagasObserver(sagaRepository).start();
        lifecycles.add(Lifecycle.of("Saga observer", _ -> sagasObserver.shutdown()));

        LOG.info("Initializing saga thread-pool ...");

        SelectableThreadPoolExecutor sagaThreadPool = sagaThreadPool(configuration);
        // a saga that branches hands steps to this pool, so such a saga fails if it runs when the pool is shut down
        lifecycles.add(Lifecycle.of("Saga thread pool", _ -> shutdownAndAwaitTermination(sagaThreadPool)));
        int numberOfSagaLogs = configuration.getInt("saga.number-of-logs");

        boolean sagaCommandsEnabled = configuration.getBoolean("saga.commands.enabled", false);

        LOG.info("Initializing saga-recovery thread-pool ...");

        AtomicInteger executorThreadId = new AtomicInteger();
        ScheduledExecutorService recoveryThreadPool = Executors.newScheduledThreadPool(SAGA_RECOVERY_THREADS, runnable -> new Thread(runnable, "saga-recovery-" + executorThreadId.incrementAndGet()));

        LOG.info("Initializing saga-execution-coordinator ...");

        SagaExecutionCoordinator sec = new SagaExecutionCoordinator(sagaLogPool, numberOfSagaLogs, sagaRepository, sagasObserver, sagaThreadPool, sagaCommandsEnabled, recoveryThreadPool);

        /* Removed Hystrix thread pool initialization - can be replaced with Resilience4j if needed. Init goes here. */

        LOG.info("Initializing saga-recovery-trigger ...");

        boolean sagaRecoveryEnabled = configuration.getBoolean("saga.recovery.enabled", false);
        SagaRecoveryTrigger sagaRecoveryTrigger = null;
        if (sagaRecoveryEnabled) {
            int intervalMinSec = configuration.getInt("saga.recovery.interval.seconds.min");
            int intervalMaxSec = configuration.getInt("saga.recovery.interval.seconds.max");
            sagaRecoveryTrigger = new SagaRecoveryTrigger(sec, intervalMinSec, intervalMaxSec, recoveryThreadPool);
        }
        lifecycles.add(sagas(sec, sagaRecoveryTrigger, recoveryThreadPool));

        LOG.info("Initializing namespace-controller ...");

        NamespaceController namespaceController = new NamespaceController(
                configuration.find("namespace.default").orElse(null),
                specification,
                specification,
                persistence,
                new NamespaceController.Writes(sec, sagaRepository,
                        indexing == null ? null : new SearchWait(indexing.queue(), indexing.index(),
                                REQUEST_THREADS / 2)),
                txlogRawdataPool
        );

        SagaParts sagas = new SagaParts(sec, sagaRepository, sagasObserver, sagaRecoveryTrigger, sagaLogPool,
                sagaThreadPool, txlogRawdataPool);
        return new UndertowApplication(new Parts(specification, persistence, storeReads, sagas, namespaceController,
                indexing, lifecycles), port, configuration);
    }

    /*
     * Search, when the configuration names an index: the index, its queue, the worker that sends the queue to it,
     * and the rebuild when the start asks for it; null without an index.
     */
    private static Indexing indexing(DynamicConfiguration configuration, Specification specification,
                                     Set<String> notSearched, RxJsonPersistence persistence,
                                     SagaRepository.Builder sagaRepositoryBuilder, Lifecycles lifecycles) {
        SearchIndex searchIndex = SearchIndexConfigurator.configureSearchIndex(configuration);
        if (searchIndex == null) {
            return null;
        }
        lifecycles.add(Lifecycle.of("Search index", _ -> searchIndex.close()));
        LOG.info("Initializing index queue ...");
        IndexQueue indexQueue = IndexQueueConfigurator.configureIndexQueue(configuration);
        lifecycles.add(Lifecycle.of("Index queue", _ -> indexQueue.close()));
        sagaRepositoryBuilder.indexQueue(indexQueue);
        IndexQueueWorker worker = new IndexQueueWorker(indexQueue, searchIndex, persistence,
                new IndexEntries(specification, notSearched), INDEX_QUEUE_POLL);
        // after the queue and the index it sends to, and before the sagas, which add to the queue
        lifecycles.add(Lifecycle.of("Index queue worker", worker::start,
                timeout -> worker.stop(timeout.toMillis(), TimeUnit.MILLISECONDS)));
        IndexRebuild rebuild = null;
        if (configuration.getBoolean("search.index.rebuild", false)) {
            IndexRebuild started = new IndexRebuild(persistence, indexQueue, searchIndex,
                    configuration.find("namespace.default").orElseThrow(),
                    List.copyOf(specification.getManagedDomains()));
            lifecycles.add(Lifecycle.of("Index rebuild", started::start, _ -> started.stop()));
            rebuild = started;
        }
        return new Indexing(searchIndex, indexQueue, worker, rebuild);
    }

    /* The bounded pool the steps of the sagas run on. */
    private static SelectableThreadPoolExecutor sagaThreadPool(DynamicConfiguration configuration) {
        final AtomicLong nextWorkerId = new AtomicLong(1);
        int sagaThreadPoolQueueCapacity = configuration.getInt("saga.threadpool.queue.capacity");
        int sagaThreadPoolCoreSize = configuration.getInt("saga.threadpool.core");
        if (sagaThreadPoolQueueCapacity >= sagaThreadPoolCoreSize) {
            LOG.warn("Configuration: saga.threadpool.core ({}) must be greater than saga.threadpool.queue.capacity ({}) in order to avoid potential deadlocks.",
                    sagaThreadPoolCoreSize, sagaThreadPoolQueueCapacity);
        }
        return new SelectableThreadPoolExecutor(
                sagaThreadPoolCoreSize, configuration.getInt("saga.threadpool.max"),
                configuration.getInt("saga.threadpool.keepalive.seconds"), TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(sagaThreadPoolQueueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("sec-" + nextWorkerId.getAndIncrement());
                    thread.setUncaughtExceptionHandler((_, e) -> LOG.error("Uncaught exception in thread {}", thread.getName(), e));
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /*
     * The sagas: started, the local sagas that did not complete are recovered, then the trigger recovers those of the
     * cluster regularly. Stopped, the recovery stops first, then the sagas that are running complete (they still have
     * their thread pool, their saga logs and the stores they write to), then the coordinator.
     */
    private static Lifecycle sagas(SagaExecutionCoordinator sec, SagaRecoveryTrigger trigger,
                                   ScheduledExecutorService recoveryThreadPool) {
        return Lifecycle.of("Sagas", () -> {
            if (trigger != null) {
                // recover local saga logs at once, then the cluster's regularly
                sec.completeLocalIncompleteSagas(sec.getRecoveryThreadPool()).handle((_, t) -> {
                    if (t != null) {
                        LOG.error("Error during initial local saga-logs recovery attempt", t);
                    }
                    LOG.info("Starting saga-recovery-trigger ...");
                    trigger.start();
                    return null;
                });
            }
        }, timeout -> {
            if (trigger != null) {
                trigger.stop(); // shuts the recovery thread-pool down as well
            } else {
                // recovery by the next write uses this pool also when there is no trigger
                shutdownAndAwaitTermination(recoveryThreadPool);
            }
            if (!sec.awaitRunningSagas(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("Sagas were still running after {}. They fail when their thread pool is shut down, and are "
                        + "recovered from their saga logs.", timeout);
            }
            sec.shutdown();
        });
    }

    private static JsonSchemaBasedSpecification createJsonSpecification(SequencedMap<String, ObjectNode> jsonMap) {
        JsonSchemaBasedSpecification jsonSchemaBasedSpecification = null;
        JsonSchema jsonSchema = null;
        for (Map.Entry<String, ObjectNode> item : jsonMap.entrySet()) {
            jsonSchema = new JsonSchemaBuilder(jsonSchema, item.getKey(), item.getValue()).build();
            jsonSchemaBasedSpecification = SpecificationJsonSchemaBuilder.createBuilder(jsonSchema).build();
        }

        return jsonSchemaBasedSpecification;
    }

    private static SagaLogPool configureSagaLogProvider(DynamicConfiguration configuration) {
        String providerClass = configuration.find("sagalog.provider").orElse(null);
        LOG.info("Using saga-log-pool provider-class: {}", providerClass);
        ServiceLoader<SagaLogInitializer> loader = ServiceLoader.load(SagaLogInitializer.class);
        SagaLogInitializer initializer = loader.stream().filter(c -> providerClass.equals(c.type().getName())).findFirst().orElseThrow().get();
        Map<String, String> providerConfig = subMapFromPrefix(configuration.asMap(), "sagalog.config.");
        checkSagaLogPool(initializer, providerConfig, configuration.getInt("saga.number-of-logs"));

        int retryIntervalSeconds = configuration.getInt("sagalog.provider.initialization.retry-interval-seconds");
        int maxWaitSeconds = configuration.getInt("sagalog.provider.initialization.max-wait-seconds");
        long start = System.currentTimeMillis();
        for (int i = 1; ; i++) {
            try {
                LOG.info("SagaLogPool provider initialization attempt # {}", i);
                SagaLogPool sagaLogPool = initializer.initialize(providerConfig);
                LOG.info("SagaLogPool initialized!");
                return sagaLogPool;
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - start;
                long remainingMs = TimeUnit.SECONDS.toMillis(maxWaitSeconds) - durationMs;
                if (remainingMs > 0) {
                    try {
                        Thread.sleep(Math.min(Jitter.around(retryIntervalSeconds * 1000L), remainingMs));
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while attempting to sleep, interrupt status preserved.", e);
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    /*
     * The threads that recover sagas: each recovers one saga log at a time, and a log of another instance of the
     * cluster is opened for it, and closed when it is done.
     */
    static final int SAGA_RECOVERY_THREADS = 10;

    /*
     * The key of a saga log provider whose saga logs each hold a connection of a pool while they are open (PostgreSQL).
     */
    static final String SAGA_LOG_POOL_SIZE = "connection-pool.max-size";

    /*
     * Each saga log of a provider with a pool of connections holds one connection while it is open, and a log is
     * opened when a write first takes it, so a pool too small for the logs shows only under load, as writes that
     * wait and fail. It is refused at start: the pool must have a connection for each of its logs, which stay open
     * once opened, one for each log of another instance that recovery opens at the same time (one for each recovery
     * thread), and one for the pool's own queries (listing the logs). The pool's size is the provider's key, or its
     * default. Whether the database has room for the pool, the provider checks when it makes it.
     */
    static void checkSagaLogPool(SagaLogInitializer initializer, Map<String, String> providerConfig, int numberOfLogs) {
        String poolSize = providerConfig.getOrDefault(SAGA_LOG_POOL_SIZE,
                initializer.configurationOptionsAndDefaults().get(SAGA_LOG_POOL_SIZE));
        if (poolSize == null) {
            return; // the provider keeps its logs without a pool of connections
        }
        int connections = Integer.parseInt(poolSize.trim());
        int needed = numberOfLogs + SAGA_RECOVERY_THREADS + 1;
        if (connections < needed) {
            throw new IllegalStateException("The saga log has " + connections + " connections in its pool"
                    + " (sagalog.config." + SAGA_LOG_POOL_SIZE + ") for " + numberOfLogs + " saga logs"
                    + " (saga.number-of-logs): each open saga log holds a connection, recovery opens up to "
                    + SAGA_RECOVERY_THREADS + " logs of other instances at once, and the pool needs one for its own"
                    + " queries. Give the pool at least " + needed + " connections, or use fewer saga logs.");
        }
    }

    /*
     * The namespace and the names of the entity types are parts of every key a store keeps, and are checked once, at
     * start, against the room the stores have (Limits).
     */
    static void checkNames(String namespace, Set<String> entityTypes) {
        if (Limits.utf8Length(namespace) > Limits.NAMESPACE_BYTES) {
            throw new IllegalStateException("The namespace is at most " + Limits.NAMESPACE_BYTES + " bytes in UTF-8: "
                    + shown(namespace));
        }
        for (String entityType : new TreeSet<>(entityTypes)) {
            if (Limits.utf8Length(entityType) > Limits.ENTITY_BYTES) {
                throw new IllegalStateException("The name of an entity type is at most " + Limits.ENTITY_BYTES
                        + " bytes in UTF-8: " + shown(entityType));
            }
        }
    }

    /*
     * A name as a message shows it: whole, or its first 80 characters and "..." when it is longer.
     */
    private static String shown(String name) {
        return name.length() <= 80 ? name : name.substring(0, 80) + "...";
    }

    public static RawdataClient configureTxLogRawdataClient(DynamicConfiguration configuration) {
        String provider = configuration.find("txlog.rawdata.provider").orElse(null);
        LOG.info("Using transaction-log provider: {}", provider);
        Map<String, String> providerConfig = new LinkedHashMap<>(subMapFromPrefix(configuration.asMap(), "txlog.config."));
        providerConfig.put(RawdataClient.PROVIDER_KEY, provider);

        int retryIntervalSeconds = configuration.getInt("txlog.rawdata.provider.initialization.retry-interval-seconds");
        int maxWaitSeconds = configuration.getInt("txlog.rawdata.provider.initialization.max-wait-seconds");
        long start = System.currentTimeMillis();
        for (int i = 1; ; i++) {
            try {
                LOG.info("Transaction-log provider initialization attempt # {}", i);
                RawdataClient txLogRawdataClient = RawdataClient.create(providerConfig);
                LOG.info("Transaction-log initialized");
                return txLogRawdataClient;
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - start;
                long remainingMs = TimeUnit.SECONDS.toMillis(maxWaitSeconds) - durationMs;
                if (remainingMs > 0) {
                    try {
                        Thread.sleep(Math.min(Jitter.around(retryIntervalSeconds * 1000L), remainingMs));
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while attempting to sleep, interrupt status preserved.", e);
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    public static Map<String, String> subMapFromPrefix(Map<String, String> configMap, String prefix) {
        NavigableMap<String, String> navConf;
        if (configMap instanceof NavigableMap<String, String> navigableMap) {
            navConf = navigableMap;
        } else {
            navConf = new TreeMap<>(configMap);
        }
        return navConf.subMap(prefix, true, prefix + "\uFFFF", false)
                .entrySet().stream().collect(Collectors.toMap(e -> e.getKey().substring(prefix.length()), Map.Entry::getValue));
    }

    static void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOG.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException _) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void enableSagaExecutionAutomaticDeadlockDetectionAndResolution() {
        sec.startThreadpoolWatchdog();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public String name() {
        return "Linked Data Store";
    }

    /**
     * Starts the subsystems, in the order they need each other (see {@link #getLifecycles()}). When one cannot
     * start, every subsystem is stopped and the start fails.
     */
    @Override
    public void start() {
        lifecycles.start(STOP_TIMEOUT);
        LOG.info("Started Linked Data Store server. PID {}", ProcessHandle.current().pid());
        LOG.info("Listening on {}:{}", host, port);
    }

    /**
     * Stops the application, one subsystem at a time, in the reverse order of the one they were made in, so that a
     * subsystem stops before what it needs does:
     * <ol>
     * <li>The HTTP server: a request that arrives from here on is answered 503 Service Unavailable, while the
     * requests that are being handled are completed; then it stops. Then the readiness checks.</li>
     * <li>The sagas: the recovery stops, the sagas that are running complete, then the coordinator stops.</li>
     * <li>The thread pool of the sagas, the saga observer and the transaction log.</li>
     * <li>With search, the rebuild, the worker of the index queue, the queue and the search index.</li>
     * <li>The saga log pool, the threads the store is read on, and the store.</li>
     * </ol>
     * A subsystem that fails to stop is logged, and those after it are stopped all the same. The application is
     * stopped once: a second call does nothing, and does not wait for the first to be done.
     */
    public void stop() {
        stop(STOP_TIMEOUT);
    }

    /**
     * Stops the application as {@link #stop()} does, waiting the given time at most for the requests that are
     * being handled, then for the running sagas, and then for each subsystem's work.
     *
     * @param timeout the longest time to wait for the work of each subsystem
     */
    @Override
    public void stop(Duration timeout) {
        if (lifecycles.stop(timeout)) {
            LOG.info("Leaving.. Bye!");
        }
    }

    /** How long the stop waits, at most, for the requests, then for the running sagas, then for each subsystem. */
    public static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The subsystems of the application, in the order they are started.
     *
     * @return the subsystems
     */
    public Lifecycles getLifecycles() {
        return lifecycles;
    }

    public RxJsonPersistence getPersistence() {
        return persistence;
    }

    public SagaExecutionCoordinator getSec() {
        return sec;
    }

    public SagaRepository getSagaRepository() {
        return sagaRepository;
    }

    public SagasObserver getSagasObserver() {
        return sagasObserver;
    }

    public SagaLogPool getSagaLogPool() {
        return sagaLogPool;
    }

    public SelectableThreadPoolExecutor getSagaThreadPool() {
        return sagaThreadPool;
    }

    public SagaRecoveryTrigger getSagaRecoveryTrigger() {
        return sagaRecoveryTrigger;
    }

    /**
     * How many requests LDS handles at once: the worker threads of Undertow.
     *
     * @return the request threads
     */
    public int getRequestThreads() {
        return REQUEST_THREADS;
    }

    /**
     * The threads the store is read on.
     *
     * @return the threads the store is read on
     */
    public StoreReads getStoreReads() {
        return storeReads;
    }

    /**
     * The locks of the documents: the writes of one document take turns.
     *
     * @return the locks of the documents
     */
    public DocumentLocks getDocumentLocks() {
        return documentLocks;
    }

    public Undertow getUndertowServer() {
        return server;
    }

    public Specification getSpecification() {
        return specification;
    }

    public TxlogRawdataPool getTxlogRawdataPool() {
        return txlogRawdataPool;
    }
}
