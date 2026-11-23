package io.descoped.lds.core.domain.managed;

import io.descoped.lds.core.domain.SyncParameter;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Limits;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.Range;
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
import io.reactivex.rxjava3.core.Flowable;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.SagaHandoffResult;
import no.cantara.saga.execution.adapter.AdapterLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Deque;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static java.util.Optional.ofNullable;

public class ManagedResourceHandler implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ManagedResourceHandler.class);
    private static final String TEXT_PLAIN = "text/plain";
    private static final String APPLICATION_JSON = "application/json";

    private final RxJsonPersistence persistence;
    private final Specification specification;
    private final SchemaRepository schemaRepository;
    private final ResourceContext resourceContext;
    private final SagaExecutionCoordinator sec;
    private final SagaRepository sagaRepository;
    private final BodyParser bodyParser = new BodyParser();

    public ManagedResourceHandler(RxJsonPersistence persistence, Specification specification, SchemaRepository schemaRepository, ResourceContext resourceContext, SagaExecutionCoordinator sec, SagaRepository sagaRepository) {
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
            getManaged(exchange);
        } else if (exchange.getRequestMethod().equalToString("put")) {
            putManaged(exchange);
        } else if (exchange.getRequestMethod().equalToString("post")) {
            putManaged(exchange);
        } else if (exchange.getRequestMethod().equalToString("delete")) {
            deleteManaged(exchange);
        } else {
            exchange.setStatusCode(400);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
            exchange.getResponseSender().send("Unsupported managed resource method: " + exchange.getRequestMethod());
        }
    }

    private void getManaged(HttpServerExchange exchange) {
        ResourceElement topLevelElement = resourceContext.getFirstElement();

        boolean isManagedList = topLevelElement.id() == null;

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");

        if (isManagedList && exchange.getQueryParameters().containsKey("schema")) {
            String jsonSchema = schemaRepository.getJsonSchema().getSchemaJson(resourceContext.getFirstElement().name());
            exchange.getResponseSender().send(jsonSchema, StandardCharsets.UTF_8);
            return;
        }

        try (Transaction tx = persistence.createTransaction(true)) {
            if (isManagedList) {
                exchange.getResponseSender().send(JsonTools.toJson(documents(tx, topLevelElement)), StandardCharsets.UTF_8);
            } else if (exchange.getQueryParameters().containsKey("timeline")) {
                ArrayNode output = timeline(tx, topLevelElement);
                if (output.size() == 0) {
                    exchange.setStatusCode(StatusCodes.NOT_FOUND).endExchange();
                    return;
                }
                exchange.getResponseSender().send(JsonTools.toJson(output), StandardCharsets.UTF_8);
            } else {
                JsonDocument jsonDocument = persistence.readDocument(tx, resourceContext.getTimestamp(), resourceContext.getNamespace(), topLevelElement.name(), topLevelElement.id()).blockingGet();
                if (jsonDocument != null && !jsonDocument.deleted()) {
                    exchange.getResponseSender().send(JsonTools.toJson(jsonDocument.jackson()), StandardCharsets.UTF_8);
                } else {
                    exchange.setStatusCode(StatusCodes.NOT_FOUND);
                }
            }
        }
        exchange.endExchange();
    }

    /*
     * The documents of the entity at the time of the request, without those that are deleted.
     */
    private ArrayNode documents(Transaction tx, ResourceElement topLevelElement) {
        Iterable<JsonDocument> documents = persistence.readDocuments(tx, resourceContext.getTimestamp(), resourceContext.getNamespace(), topLevelElement.name(), Range.unbounded()).blockingIterable();
        ArrayNode output = mapper.createArrayNode();
        for (JsonDocument jsonDocument : documents) {
            if (!jsonDocument.deleted()) {
                output.add(jsonDocument.jackson());
            }
        }
        return output;
    }

    /*
     * Every version of the document, oldest first. There is no pagination and no range of time: the store is asked
     * for all versions.
     */
    private ArrayNode timeline(Transaction tx, ResourceElement topLevelElement) {
        ArrayNode output = mapper.createArrayNode();
        Flowable<JsonDocument> jsonDocumentFlowable = persistence.readDocumentVersions(tx, resourceContext.getNamespace(), topLevelElement.name(), topLevelElement.id(), Range.unbounded());
        for (JsonDocument jsonDocument : jsonDocumentFlowable.blockingIterable()) {
            ObjectNode timeVersionedInstance = output.addObject();
            timeVersionedInstance.put("version", jsonDocument.key().timestamp().toString());
            timeVersionedInstance.set("document", jsonDocument.jackson());
        }
        return output;
    }

    private void putManaged(HttpServerExchange exchange) {
        ResourceElement topLevelElement = resourceContext.getFirstElement();
        String namespace = resourceContext.getNamespace();
        String managedDomain = topLevelElement.name();
        String managedDocumentId = topLevelElement.id();

        exchange.getRequestReceiver().receiveFullString(
                (httpServerExchange, requestBody) -> {
                    // check if we received an empty payload
                    if ("".equals(requestBody)) {
                        LOG.error("Received empty payload for: {}", exchange.getRequestPath());
                        exchange.setStatusCode(400);
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
                        exchange.getResponseSender().send("Payload was empty!");
                        return;
                    }

                    // check that we have a document id.
                    if (managedDocumentId == null || "".equals(managedDocumentId)) {
                        exchange.setStatusCode(400);
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
                        exchange.getResponseSender().send("Id was empty!");
                        return;
                    }

                    String contentType = ofNullable(exchange.getRequestHeaders().get(Headers.CONTENT_TYPE))
                            .map(HeaderValues::getFirst).orElse(APPLICATION_JSON);
                    JsonNode requestData;
                    try {
                        requestData = bodyParser.deserializeBody(contentType, requestBody);
                    } catch (BodyException e) {
                        exchange.setStatusCode(e.status());
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
                        exchange.getResponseSender().send(e.getMessage());
                        return;
                    }

                    if (LOG.isTraceEnabled()) {
                        LOG.trace("{} {}\n{}", exchange.getRequestMethod(), exchange.getRequestPath(), requestBody);
                    }

                    NullFields.removeFrom(requestData);

                    String overLimit = Limits.overLimit(requestData);
                    if (overLimit != null) {
                        exchange.setStatusCode(400);
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
                        exchange.getResponseSender().send(overLimit);
                        return;
                    }

                    try {
                        LinkedDocumentValidator validator = new LinkedDocumentValidator(specification, schemaRepository);
                        validator.validate(managedDomain, requestData);
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
                    SagaInput sagaInput = new SagaInput(sec.generateTxId(), "PUT", "TODO", new DocumentKey(namespace, managedDomain, managedDocumentId, resourceContext.getTimestamp()), source, sourceId, requestData);
                    SelectableFuture<SagaHandoffResult> handoff = sec.handoff(sync, adapterLoader, saga, sagaInput, SagaCommands.getSagaAdminParameterCommands(httpServerExchange),
                completion -> DocumentLocks.holdUntil(httpServerExchange, completion));
                    SagaHandoffResult handoffResult = handoff.join();

                    exchange.setStatusCode(StatusCodes.CREATED);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, APPLICATION_JSON);
                    exchange.getResponseSender().send("{\"saga-execution-id\":\"" + handoffResult.getExecutionId() + "\"}");
                },
                (_, e) -> {
                    exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
                    exchange.getResponseSender().send("Error: " + e.getMessage());
                    LOG.warn("", e);
                },
                StandardCharsets.UTF_8);
    }

    private void deleteManaged(HttpServerExchange exchange) {
        ResourceElement topLevelElement = resourceContext.getFirstElement();
        String managedDomain = topLevelElement.name();

        // check that we have a document id, as for PUT
        if (topLevelElement.id() == null || "".equals(topLevelElement.id())) {
            exchange.setStatusCode(400);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
            exchange.getResponseSender().send("Id was empty!");
            return;
        }

        boolean sync = SyncParameter.waits(exchange.getQueryParameters());

        Saga saga = sagaRepository.get(SagaRepository.SAGA_DELETE_MANAGED_RESOURCE);

        String source = ofNullable(exchange.getQueryParameters().get("source")).map(Deque::peekFirst).orElse(null);
        String sourceId = ofNullable(exchange.getQueryParameters().get("sourceId")).map(Deque::peekFirst).orElse(null);

        AdapterLoader adapterLoader = sagaRepository.getAdapterLoader();
        SagaInput sagaInput = new SagaInput(sec.generateTxId(), "DELETE", "TODO", new DocumentKey(resourceContext.getNamespace(), managedDomain, topLevelElement.id(), resourceContext.getTimestamp()), source, sourceId, null);
        SelectableFuture<SagaHandoffResult> handoff = sec.handoff(sync, adapterLoader, saga, sagaInput, SagaCommands.getSagaAdminParameterCommands(exchange),
                completion -> DocumentLocks.holdUntil(exchange, completion));
        SagaHandoffResult handoffResult = handoff.join();

        HeaderMap responseHeaders = exchange.getResponseHeaders();
        if (sync) {
            // Workaround https://bugs.openjdk.java.net/browse/JDK-8211437
            // 204 MUST come with a content-size of 0.
            responseHeaders.add(Headers.CONTENT_LENGTH, 0);
            exchange.setStatusCode(StatusCodes.NO_CONTENT);
            exchange.endExchange();
        } else {
            exchange.setStatusCode(StatusCodes.ACCEPTED);
            responseHeaders.put(Headers.CONTENT_TYPE, APPLICATION_JSON);
            exchange.getResponseSender().send("{\"saga-execution-id\":\"" + handoffResult.getExecutionId() + "\"}");
        }


    }
}
