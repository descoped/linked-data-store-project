package io.descoped.lds.core;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.core.controller.CORSHandler;
import io.descoped.lds.core.controller.HealthCheckHandler;
import io.descoped.lds.core.controller.NamespaceController;
import io.descoped.lds.core.persistence.PersistenceConfigurator;
import io.descoped.lds.core.saga.SagaExecutionCoordinator;
import io.descoped.lds.core.saga.SagaRecoveryTrigger;
import io.descoped.lds.core.saga.SagaRepository;
import io.descoped.lds.core.saga.SagasObserver;
import io.descoped.lds.core.schema.JsonSchema;
import io.descoped.lds.core.schema.JsonSchemaBuilder;
import io.descoped.lds.core.search.SearchIndexConfigurator;
import io.descoped.lds.core.specification.JsonSchemaBasedSpecification;
import io.descoped.lds.core.specification.SpecificationJsonSchemaBuilder;
import io.descoped.lds.core.txlog.TxlogRawdataPool;
import io.descoped.lds.graphql.GraphqlHttpHandler;
import io.descoped.lds.graphql.jsonschema.GraphQLToJsonConverter;
import io.descoped.lds.graphql.schemas.GraphQLSchemaBuilder;
import io.descoped.rawdata.api.RawdataClient;
import io.undertow.Handlers;
import io.undertow.Undertow;
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
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

public class UndertowApplication {

    private static final Logger LOG = LoggerFactory.getLogger(UndertowApplication.class);
    private final RxJsonPersistence persistence;
    private final Specification specification;
    private final Undertow server;
    /** In front of every handler but the CORS handler: counts the requests in flight, and refuses new ones when the application stops. */
    private final GracefulShutdownHandler requests;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final String host;
    private final int port;
    private final SagaExecutionCoordinator sec;
    private final SagaRepository sagaRepository;
    private final SagasObserver sagasObserver;
    private final SagaLogPool sagaLogPool;
    private final SelectableThreadPoolExecutor sagaThreadPool;
    private final SagaRecoveryTrigger sagaRecoveryTrigger;
    private final TxlogRawdataPool txlogRawdataPool;

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

    UndertowApplication(Specification specification, RxJsonPersistence persistence, SagaParts sagas, int port,
                        NamespaceController namespaceController, SearchIndex searchIndex,
                        DynamicConfiguration configuration) {
        this.specification = specification;
        this.sagaRecoveryTrigger = sagas.recoveryTrigger();
        this.host = configuration.find("http.host").orElse(null);
        this.port = port;
        this.persistence = persistence;
        this.sec = sagas.sec();
        this.sagaRepository = sagas.repository();
        this.sagasObserver = sagas.observer();
        this.sagaLogPool = sagas.logPool();
        this.sagaThreadPool = sagas.threadPool();
        this.txlogRawdataPool = sagas.txlogRawdataPool();

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

            GraphQLSchemaBuilder schemaBuilder = new GraphQLSchemaBuilder(namespace, persistence, searchIndex);
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

        HealthCheckHandler healthHandler = new HealthCheckHandler(persistence);
        pathHandler.addExactPath(HealthCheckHandler.HEALTH_ALIVE_PATH, healthHandler);
        ResponseCodeHandler aliveHandler = new ResponseCodeHandler(StatusCodes.OK);
        pathHandler.addExactPath(HealthCheckHandler.HEALTH_READY_PATH, aliveHandler);
        pathHandler.addExactPath(HealthCheckHandler.PING_PATH, aliveHandler);
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

        List<Pattern> corsAllowOrigin = Stream.of(configuration.find("http.cors.allow.origin").orElse(null)
                .split(",")).map(Pattern::compile).toList();
        Set<String> corsAllowMethods = Set.of(configuration.find("http.cors.allow.methods").orElse(null)
                .split(","));
        Set<String> corsAllowHeaders = Set.of(configuration.find("http.cors.allow.header").orElse(null)
                .split(","));
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
                .setHandler(corsHandler)
                .build();
    }

    private static TypeDefinitionRegistry parseSchemaFile(File graphQLFile) {
        TypeDefinitionRegistry definitionRegistry;
        URL systemResource = ClassLoader.getSystemResource(graphQLFile.getPath());

        if (ofNullable(systemResource).isPresent()) {
            definitionRegistry = new SchemaParser().parse(new File(systemResource.getPath()));
        } else {
            definitionRegistry = new SchemaParser().parse(new File(graphQLFile.getPath()));
        }
        return definitionRegistry;
    }

    /** The class path resource that holds the defaults of the configuration of core */
    public static final String DEFAULT_CONFIGURATION_RESOURCE_PATH = "application-defaults.properties";

    public static UndertowApplication initializeUndertowApplication(DynamicConfiguration configuration) {
        int port = configuration.getInt("http.port");
        return initializeUndertowApplication(configuration, port);
    }

    public static UndertowApplication initializeUndertowApplication(DynamicConfiguration configuration, int port) {
        LOG.info("Initializing Linked Data Store (LDS) server ...");

        LOG.info("Initializing specification ...");

        JsonSchemaBasedSpecification specification;

        Optional<String> graphQLSchemaPath = configuration.find("graphql.schema")
                .map(path -> path.isEmpty() ? null : path);

        if (graphQLSchemaPath.isPresent()) {
            File graphQLFile = new File(graphQLSchemaPath.get());

            TypeDefinitionRegistry definitionRegistry = parseSchemaFile(graphQLFile);

            GraphQLSchema schema = GraphQLSchemaBuilder.parseSchema(definitionRegistry);
            GraphQLToJsonConverter graphQLToJsonConverter = new GraphQLToJsonConverter(schema);
            SequencedMap<String, ObjectNode> jsonMap = graphQLToJsonConverter.createSpecification(schema);

            LOG.info("Creating specification using GraphQL schema: {}", graphQLSchemaPath.get());
            specification = createJsonSpecification(jsonMap);

        } else {
            String schemaConfigStr = configuration.get("specification.schema");
            String[] specificationSchema = ("".equals(schemaConfigStr) ? new String[0] : schemaConfigStr.split(","));
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

        LOG.info("Initializing primary persistence ...");

        RxJsonPersistence persistence = PersistenceConfigurator.configurePersistence(configuration, specification);

        LOG.info("Initializing saga-log pool ...");

        SagaLogPool sagaLogPool = configureSagaLogProvider(configuration);

        SagaRepository.Builder sagaRepositoryBuilder = new SagaRepository.Builder()
                .specification(specification)
                .persistence(persistence);

        LOG.info("Initializing search-index ...");

        SearchIndex searchIndex = SearchIndexConfigurator.configureSearchIndex(configuration);
        if (searchIndex != null) {
            sagaRepositoryBuilder.indexer(searchIndex);
        }

        LOG.info("Initializing transaction-log ...");

        RawdataClient txLogClient = configureTxLogRawdataClient(configuration);
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

        LOG.info("Initializing saga thread-pool ...");

        final AtomicLong nextWorkerId = new AtomicLong(1);
        int sagaThreadPoolQueueCapacity = configuration.getInt("saga.threadpool.queue.capacity");
        int sagaThreadPoolCoreSize = configuration.getInt("saga.threadpool.core");
        if (sagaThreadPoolQueueCapacity >= sagaThreadPoolCoreSize) {
            LOG.warn("Configuration: saga.threadpool.core ({}) must be greater than saga.threadpool.queue.capacity ({}) in order to avoid potential deadlocks.",
                    sagaThreadPoolCoreSize, sagaThreadPoolQueueCapacity);
        }
        SelectableThreadPoolExecutor sagaThreadPool = new SelectableThreadPoolExecutor(
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
        int numberOfSagaLogs = configuration.getInt("saga.number-of-logs");

        boolean sagaCommandsEnabled = configuration.getBoolean("saga.commands.enabled", false);

        LOG.info("Initializing saga-recovery thread-pool ...");

        AtomicInteger executorThreadId = new AtomicInteger();
        ScheduledExecutorService recoveryThreadPool = Executors.newScheduledThreadPool(10, runnable -> new Thread(runnable, "saga-recovery-" + executorThreadId.incrementAndGet()));

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

        LOG.info("Initializing namespace-controller ...");

        NamespaceController namespaceController = new NamespaceController(
                configuration.find("namespace.default").orElse(null),
                specification,
                specification,
                persistence,
                sec,
                sagaRepository,
                txlogRawdataPool
        );

        SagaParts sagas = new SagaParts(sec, sagaRepository, sagasObserver, sagaRecoveryTrigger, sagaLogPool,
                sagaThreadPool, txlogRawdataPool);
        return new UndertowApplication(specification, persistence, sagas, port, namespaceController, searchIndex,
                configuration);
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
                        Thread.sleep(Math.min(retryIntervalSeconds * 1000L, remainingMs));
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
                        Thread.sleep(Math.min(retryIntervalSeconds * 1000L, remainingMs));
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

    public void start() {
        LOG.info("Starting Undertow ...");
        server.start();
        if (sagaRecoveryTrigger != null) {
            LOG.info("Starting saga-recovery (instance-local) ...");
            // attempt to recover local saga-logs immediately, then attempt cluster wide recovery regularly
            sec.completeLocalIncompleteSagas(sec.getRecoveryThreadPool()).handle((_, t) -> {
                if (t != null) {
                    LOG.error("Error during initial local saga-logs recovery attempt", t);
                }
                LOG.info("Starting saga-recovery-trigger ...");
                sagaRecoveryTrigger.start();
                return null;
            });
        }
        LOG.info("Started Linked Data Store server. PID {}", ProcessHandle.current().pid());
        LOG.info("Listening on {}:{}", host, port);
    }

    /**
     * Stops the application, one part at a time, in the order the parts need each other:
     * <ol>
     * <li>No new work. A request that arrives from here on is answered 503 Service Unavailable, while the requests
     * that are being handled are completed. Then the HTTP server stops, and the recovery of unfinished sagas.</li>
     * <li>The sagas that are running complete. They still have their thread pool, their saga logs and the stores
     * they write to.</li>
     * <li>The thread pool of the sagas is shut down. A saga that branches hands steps to this pool, so such a saga
     * fails if it is running when the pool is shut down.</li>
     * <li>The saga log pool is shut down. After that it gives out no log.</li>
     * <li>The stores the sagas wrote to are closed.</li>
     * </ol>
     * A part that fails to stop is logged, and the parts after it are stopped all the same. The application is
     * stopped once: a second call does nothing, and does not wait for the first to be done.
     */
    public void stop() {
        stop(RUNNING_SAGAS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the application as {@link #stop()} does, waiting the given time at most for the requests that are
     * being handled, and then the same time at most for the sagas that are running.
     *
     * @param timeout the longest time to wait for the requests, and then for the running sagas
     * @param unit    the unit of the timeout
     */
    public void stop(long timeout, TimeUnit unit) {
        if (!stopping.compareAndSet(false, true)) {
            return; // stopped, or being stopped by another caller
        }
        stop("HTTP requests", () -> {
            requests.shutdown(); // every request from here on is answered 503 Service Unavailable
            if (!requests.awaitShutdown(unit.toMillis(timeout))) {
                LOG.warn("Requests were still being handled after {} {}. They lose their connection when the HTTP server stops.", timeout, unit);
            }
        });
        stop("Undertow", server::stop);
        if (sagaRecoveryTrigger != null) {
            stop("SagaRecoveryTrigger", sagaRecoveryTrigger::stop); // shuts the recovery thread-pool down as well
        } else {
            // recovery by the next write uses this pool also when there is no trigger
            stop("Saga recovery thread-pool", () -> shutdownAndAwaitTermination(sec.getRecoveryThreadPool()));
        }

        stop("Running sagas", () -> {
            if (!sec.awaitRunningSagas(timeout, unit)) {
                LOG.warn("Sagas were still running after {} {}. They fail when their thread pool is shut down, and are recovered from their saga logs.", timeout, unit);
            }
        });

        stop("Saga Execution Coordinator", sec::shutdown);
        stop("Saga thread-pool", () -> shutdownAndAwaitTermination(sagaThreadPool));
        stop("SagaObserver", sagasObserver::shutdown);

        stop("SagaLogPool", sagaLogPool::shutdown);

        stop("Persistence provider", persistence::close);
        stop("Transaction log (rawdata client)", () -> txlogRawdataPool.getClient().close());
        LOG.info("Leaving.. Bye!");
    }

    static final int RUNNING_SAGAS_TIMEOUT_SECONDS = 10;

    /* A part of the application that is stopped; waiting for the requests may be interrupted. */
    @FunctionalInterface
    private interface Stopping {
        void stop() throws InterruptedException;
    }

    private static void stop(String part, Stopping action) {
        try {
            action.stop();
            LOG.debug("{} was shutdown", part);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while shutting down: {}", part, e);
        } catch (Exception e) {
            LOG.error("Error while shutting down: {}", part, e);
        }
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
