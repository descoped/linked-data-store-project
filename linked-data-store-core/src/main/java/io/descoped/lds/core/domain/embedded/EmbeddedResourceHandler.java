package io.descoped.lds.core.domain.embedded;

import io.descoped.lds.core.domain.AsyncAnswer;
import io.descoped.lds.core.domain.SyncParameter;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Limits;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.core.domain.BodyException;
import io.descoped.lds.core.domain.BodyParser;
import io.descoped.lds.core.domain.DocumentLocks;
import io.descoped.lds.core.domain.NullFields;
import io.descoped.lds.core.domain.resource.ResourceContext;
import io.descoped.lds.core.domain.resource.ResourceElement;
import io.descoped.lds.core.saga.SagaCommands;
import io.descoped.lds.core.saga.SagaExecutionCoordinator;
import io.descoped.lds.core.saga.SagaInput;
import io.descoped.lds.core.saga.SagaRepository;
import io.descoped.lds.core.schema.SchemaRepository;
import io.descoped.lds.core.validation.LinkedDocumentValidationException;
import io.descoped.lds.core.validation.LinkedDocumentValidator;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.SagaHandoffResult;
import no.cantara.saga.execution.adapter.AdapterLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Deque;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static java.util.Optional.ofNullable;

public class EmbeddedResourceHandler implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedResourceHandler.class);
    private static final String TEXT_PLAIN = "text/plain";
    private static final String APPLICATION_JSON = "application/json";

    private final Specification specification;
    private final SchemaRepository schemaRepository;
    private final ResourceContext resourceContext;
    private final SagaExecutionCoordinator sec;
    private final RxJsonPersistence persistence;
    private final SagaRepository sagaRepository;
    private final BodyParser bodyParser = new BodyParser();

    public EmbeddedResourceHandler(RxJsonPersistence persistence, Specification specification, SchemaRepository schemaRepository, ResourceContext resourceContext, SagaExecutionCoordinator sec, SagaRepository sagaRepository) {
        this.persistence = persistence;
        this.specification = specification;
        this.schemaRepository = schemaRepository;
        this.resourceContext = resourceContext;
        this.sec = sec;
        this.sagaRepository = sagaRepository;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.getRequestMethod().equalToString("get")) {
            getEmbedded(exchange);
        } else if (exchange.getRequestMethod().equalToString("put")) {
            putEmbedded(exchange);
        } else if (exchange.getRequestMethod().equalToString("post")) {
            putEmbedded(exchange);
        } else if (exchange.getRequestMethod().equalToString("delete")) {
            deleteEmbedded(exchange);
        } else {
            exchange.setStatusCode(400);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
            exchange.getResponseSender().send("Unsupported embedded resource method: " + exchange.getRequestMethod());
        }
    }

    private void getEmbedded(HttpServerExchange exchange) {
        ResourceElement topLevelElement = resourceContext.getFirstElement();

        JsonNode jsonNode;
        try (Transaction tx = persistence.createTransaction(true)) {
            JsonDocument jsonDocument = persistence.readDocument(tx, resourceContext.getTimestamp(), resourceContext.getNamespace(), topLevelElement.name(), topLevelElement.id()).blockingGet();
            jsonNode = ofNullable(jsonDocument).map(JsonDocument::jackson).orElse(null);
        }

        if (jsonNode == null) {
            exchange.setStatusCode(404);
            return;
        }

        // The form of the answer depends on the json type of the sub-tree: it is not decided whether the result
        // should always be wrapped in a json-array.
        JsonNode subTreeRoot = resourceContext.subTree(jsonNode);
        String result;
        if (subTreeRoot == null) {
            result = "[null]";
        } else if (subTreeRoot.isContainer()) {
            result = JsonTools.toJson(subTreeRoot);
        } else {
            // wrap simple values in json array.
            result = JsonTools.toJson(mapper.createArrayNode().add(subTreeRoot));
        }
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(result, StandardCharsets.UTF_8);
    }

    private void putEmbedded(HttpServerExchange exchange) {
        // the body is read as it comes; the write goes on on a worker thread when it has all come
        AsyncAnswer.withBody(exchange,
                (httpServerExchange, message) -> {
                    ResourceElement topLevelElement = resourceContext.getFirstElement();
                    String namespace = resourceContext.getNamespace();
                    String managedDomain = topLevelElement.name();
                    String managedDocumentId = topLevelElement.id();

                    JsonNode managedDocument;
                    try (Transaction tx = persistence.createTransaction(true)) {
                        JsonDocument jsonDocument = persistence.readDocument(tx, resourceContext.getTimestamp(), namespace, managedDomain, managedDocumentId).blockingGet();
                        managedDocument = ofNullable(jsonDocument).map(JsonDocument::jackson).orElse(null);
                    }

                    if (managedDocument == null) {
                        exchange.setStatusCode(404);
                        return;
                    }

                    String contentType = ofNullable(exchange.getRequestHeaders().get(Headers.CONTENT_TYPE))
                            .map(HeaderValues::getFirst).orElse(APPLICATION_JSON);
                    JsonNode embeddedJson;
                    try {
                        embeddedJson = bodyParser.deserializeBody(contentType, message);
                    } catch (BodyException e) {
                        exchange.setStatusCode(e.status());
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
                        exchange.getResponseSender().send(e.getMessage());
                        return;
                    }

                    if (LOG.isTraceEnabled()) {
                        LOG.trace("{} {}\n{}", exchange.getRequestMethod(), exchange.getRequestPath(), message);
                    }

                    mergeJson(resourceContext, managedDocument, embeddedJson);
                    NullFields.removeFrom(managedDocument);

                    String overLimit = Limits.overLimit(managedDocument);
                    if (overLimit != null) {
                        exchange.setStatusCode(400);
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
                        exchange.getResponseSender().send(overLimit);
                        return;
                    }

                    try {
                        LinkedDocumentValidator validator = new LinkedDocumentValidator(specification, schemaRepository);
                        validator.validate(managedDomain, managedDocument);
                    } catch (LinkedDocumentValidationException ve) {
                        LOG.debug("Schema validation error: {}", ve.getMessage());
                        exchange.setStatusCode(400);
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
                        exchange.getResponseSender().send("Schema validation error: " + ve.getMessage());
                        return;
                    }

                    boolean sync = SyncParameter.waits(exchange.getQueryParameters());

                    Saga saga = sagaRepository.get(SagaRepository.SAGA_CREATE_OR_UPDATE_MANAGED_RESOURCE);

                    String source = ofNullable(exchange.getQueryParameters().get("source")).map(Deque::peekFirst).orElse(null);
                    String sourceId = ofNullable(exchange.getQueryParameters().get("sourceId")).map(Deque::peekFirst).orElse(null);

                    AdapterLoader adapterLoader = sagaRepository.getAdapterLoader();
                    SagaInput sagaInput = new SagaInput(sec.generateTxId(), "PUT", "TODO", new DocumentKey(namespace, managedDomain, managedDocumentId, resourceContext.getTimestamp()), source, sourceId, managedDocument);
                    SelectableFuture<SagaHandoffResult> handoff = sec.handoff(sync, adapterLoader, saga, sagaInput, SagaCommands.getSagaAdminParameterCommands(httpServerExchange),
                            completion -> DocumentLocks.holdUntil(httpServerExchange, completion));
                    AsyncAnswer.afterSaga(httpServerExchange, resourceContext, handoff, (answered, handoffResult) -> {
                        answered.setStatusCode(200);
                        answered.getResponseHeaders().put(Headers.CONTENT_TYPE, APPLICATION_JSON);
                        answered.getResponseSender().send("{\"saga-execution-id\":\"" + handoffResult.getExecutionId() + "\"}");
                    });
                },
                (_, e) -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
                    exchange.getResponseSender().send("Error putting embedded resource: " + e.getMessage());
                    LOG.warn("", e);
                });
    }

    private void deleteEmbedded(HttpServerExchange exchange) {
        // the body is read as it comes; the write goes on on a worker thread when it has all come
        AsyncAnswer.withBody(exchange,
                (httpServerExchange, _) -> {
                    ResourceElement topLevelElement = resourceContext.getFirstElement();
                    String namespace = resourceContext.getNamespace();
                    String managedDomain = topLevelElement.name();
                    String managedDocumentId = topLevelElement.id();

                    JsonNode rootNode;
                    try (Transaction tx = persistence.createTransaction(true)) {
                        JsonDocument jsonDocument = persistence.readDocument(tx, resourceContext.getTimestamp(), namespace, managedDomain, managedDocumentId).blockingGet();
                        rootNode = ofNullable(jsonDocument).map(JsonDocument::jackson).orElse(null);
                    }

                    if (rootNode == null) {
                        exchange.setStatusCode(404);
                        return;
                    }

                    // what is not there is not made: a part is removed only from a document that has it
                    resourceContext.removeFrom(rootNode);

                    boolean sync = SyncParameter.waits(exchange.getQueryParameters());

                    Saga saga = sagaRepository.get(SagaRepository.SAGA_CREATE_OR_UPDATE_MANAGED_RESOURCE);

                    String source = ofNullable(exchange.getQueryParameters().get("source")).map(Deque::peekFirst).orElse(null);
                    String sourceId = ofNullable(exchange.getQueryParameters().get("sourceId")).map(Deque::peekFirst).orElse(null);

                    AdapterLoader adapterLoader = sagaRepository.getAdapterLoader();
                    SagaInput sagaInput = new SagaInput(sec.generateTxId(), "PUT", "TODO", new DocumentKey(namespace, managedDomain, managedDocumentId, resourceContext.getTimestamp()), source, sourceId, rootNode);
                    SelectableFuture<SagaHandoffResult> handoff = sec.handoff(sync, adapterLoader, saga, sagaInput, SagaCommands.getSagaAdminParameterCommands(httpServerExchange),
                            completion -> DocumentLocks.holdUntil(httpServerExchange, completion));
                    AsyncAnswer.afterSaga(httpServerExchange, resourceContext, handoff, (answered, handoffResult) -> {
                        answered.setStatusCode(200);
                        answered.getResponseHeaders().put(Headers.CONTENT_TYPE, APPLICATION_JSON);
                        answered.getResponseSender().send("{\"saga-execution-id\":\"" + handoffResult.getExecutionId() + "\"}");
                    });
                },
                (_, e) -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
                    exchange.getResponseSender().send("Error deleting embedded resource: " + e.getMessage());
                    LOG.warn("", e);
                });
    }

    public boolean mergeJson(ResourceContext resourceContext, JsonNode documentRootNode, JsonNode subTree) {
        return resourceContext.navigateAndCreateJson(documentRootNode, t -> {
            String embeddedPropertyName = t.resourceElement.name();
            if (t.jsonObject.isArray()) {
                // array-navigation is not supported
                throw new UnsupportedOperationException("array navigation not supported");
            }
            t.jsonObject.set(embeddedPropertyName, subTree);
            return true;
        });
    }


}
