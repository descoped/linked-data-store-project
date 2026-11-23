package io.descoped.lds.graphql.schemas;

import graphql.Directives;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeResolvingVisitor;
import graphql.schema.idl.EchoingWiringFactory;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.graphql.directives.DomainDirective;
import io.descoped.lds.graphql.directives.LinkDirective;
import io.descoped.lds.graphql.directives.ReverseLinkDirective;
import io.descoped.lds.graphql.schemas.visitors.AddConnectionVisitor;
import io.descoped.lds.graphql.schemas.visitors.AddSearchTypesVisitor;
import io.descoped.lds.graphql.schemas.visitors.QueryBuildingVisitor;
import io.descoped.lds.graphql.schemas.visitors.RegistrySetupVisitor;
import io.descoped.lds.graphql.schemas.visitors.ReverseLinkBuildingVisitor;
import io.descoped.lds.graphql.schemas.visitors.ReverseLinkNameVisitor;
import io.descoped.lds.graphql.schemas.visitors.TypeReferencerVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public class GraphQLSchemaBuilder {

    private static final Logger log = LoggerFactory.getLogger(GraphQLSchemaBuilder.class);
    private static final TypeTraverser TRAVERSER = new TypeTraverser();
    private static final SchemaPrinter PRINTER = new SchemaPrinter();
    private static final String QUERY_NAME = "Query";
    private final RxJsonPersistence persistence;
    private final SearchIndex searchIndex;
    private final String namespace;
    private final GraphQLObjectType.Builder query = SchemaElements.newObject().name(QUERY_NAME);

    public GraphQLSchemaBuilder(String namespace, RxJsonPersistence persistence,
                                SearchIndex searchIndex) {
        this.persistence = persistence;
        this.searchIndex = searchIndex;
        this.namespace = namespace;
    }

    private static String printSchema(GraphQLType type) {
        return PRINTER.print(type);
    }

    private static String printSchema(Collection<? extends GraphQLType> types) {
        StringBuilder builder = new StringBuilder();
        for (GraphQLType type : types) {
            builder.append(printSchema(type));
        }
        return builder.toString();
    }

    public static GraphQLSchema parseSchema(TypeDefinitionRegistry registry) {

        RuntimeWiring.Builder runtime = RuntimeWiring.newRuntimeWiring()
                .comparatorRegistry(SchemaElements.ORDER)
                .scalar(DateTimeScalar.INSTANCE)
                .scalar(ExtendedScalars.Date)
                .scalar(ExtendedScalars.Time)
                .scalar(ExtendedScalars.GraphQLLong);

        runtime.wiringFactory(new EchoingWiringFactory());

        return new SchemaGenerator().makeExecutableSchema(
                SchemaGenerator.Options.defaultOptions(),
                withFieldInEmptyQuery(registry),
                runtime.build()
        );
    }

    /**
     * graphql-java no longer builds a schema whose Query type has no fields. LDS makes the Query type itself and
     * does not use the one of the schema file, which is there because a schema must have one and is often
     * empty. An empty one is given a field here, in a copy of the registry.
     */
    private static TypeDefinitionRegistry withFieldInEmptyQuery(TypeDefinitionRegistry registry) {
        Optional<ObjectTypeDefinition> query = Optional.ofNullable(registry.getTypeOrNull(QUERY_NAME, ObjectTypeDefinition.class));
        if (query.isEmpty() || !query.get().getFieldDefinitions().isEmpty()) {
            return registry;
        }
        TypeDefinitionRegistry copy = new TypeDefinitionRegistry().merge(registry);
        copy.remove(query.get());
        copy.add(query.get().transform(builder -> builder.fieldDefinition(
                FieldDefinition.newFieldDefinition().name("_empty").type(new TypeName("String")).build())));
        return copy;
    }

    /**
     * The names of the types that a type or a directive of the schema uses. graphql-java now keeps a scalar
     * that the schema file declares as a type of the schema even when nothing uses it. It did not when LDS was
     * written, and LDS still leaves such a scalar out.
     */
    private static Set<String> typesInUse(GraphQLSchema schema) {
        List<GraphQLSchemaElement> roots = new ArrayList<>();
        for (GraphQLNamedType type : schema.getTypeMap().values()) {
            if (!(type instanceof GraphQLScalarType)) {
                roots.add(type);
            }
        }
        roots.addAll(schema.getDirectives());
        TypeCollectingVisitor typeCollectingVisitor = new TypeCollectingVisitor();
        TRAVERSER.depthFirst(typeCollectingVisitor, roots);
        return typeCollectingVisitor.getResult().keySet();
    }

    public GraphQLSchema getGraphQL(GraphQLSchema schema) {

        Map<String, GraphQLNamedType> graphQLTypes = new TreeMap<>(schema.getTypeMap());
        graphQLTypes.remove(QUERY_NAME);
        graphQLTypes.keySet().removeIf(key -> key.startsWith("__"));
        Set<String> typesInUse = typesInUse(schema);
        graphQLTypes.values().removeIf(type -> type instanceof GraphQLScalarType && !typesInUse.contains(type.getName()));

        // Collect all the types in a type map.
        log.info("Collecting types into type map");
        TypeCollectingVisitor graphQLTypeCollectingVisitor = new TypeCollectingVisitor();
        TRAVERSER.depthFirst(graphQLTypeCollectingVisitor, graphQLTypes.values());
        Map<String, GraphQLNamedType> typeMap = graphQLTypeCollectingVisitor.getResult();

        // Reference and Resolve
        TRAVERSER.depthFirst(new TypeReferencerVisitor(typeMap), typeMap.values());
        TRAVERSER.depthFirst(new GraphQLTypeResolvingVisitor(typeMap), typeMap.values());

        // Compute reverse links
        TRAVERSER.depthFirst(new ReverseLinkNameVisitor(typeMap), typeMap.values());

        // Add the reverse links.
        TRAVERSER.depthFirst(new ReverseLinkBuildingVisitor(typeMap), typeMap.values());
        if (log.isTraceEnabled()) {
            log.trace("Computing reverse links:\n{}", printSchema(typeMap.values()));
        } else {
            log.info("Computing reverse links");
        }

        // Create the query fields.
        log.info("Creating root \"Query\" fields");
        QueryBuildingVisitor graphQLQueryVisitor = new QueryBuildingVisitor(query);
        TRAVERSER.depthFirst(graphQLQueryVisitor, typeMap.values());
        if (log.isTraceEnabled()) {
            log.trace("Query:\n{}", printSchema(graphQLQueryVisitor.getQuery()));
        }

        // Add the search fields.
        if (searchIndex != null) {
            log.info("Creating root \"Query\" search field and search types");
            AddSearchTypesVisitor addSearchTypesVisitor = new AddSearchTypesVisitor(typeMap, query);
            TRAVERSER.depthFirst(addSearchTypesVisitor, typeMap.values());
            GraphQLType queryWithSearch = addSearchTypesVisitor.getQuery();
            if (log.isTraceEnabled()) {
                log.trace("Query:\n{}", printSchema(queryWithSearch));
            }
        }

        // Done with the query at this point so add it to the type map for the next passes.
        typeMap.put(QUERY_NAME, query.build());

        log.info("Replacing all type with references");
        TRAVERSER.depthFirst(new TypeReferencerVisitor(typeMap), typeMap.values());

        // Transform with pagination
        log.info("Transforming paginated links to relay connections");
        AddConnectionVisitor addConnectionVisitor = new AddConnectionVisitor(typeMap);
        TRAVERSER.depthFirst(addConnectionVisitor, typeMap.values());

        if (log.isDebugEnabled()) {
            log.debug("Final schema:\n{}", printSchema(typeMap.values()));
        }

        Set<GraphQLDirective> directives = new LinkedHashSet<>(Set.of(
                DomainDirective.INSTANCE.getDirective(),
                LinkDirective.INSTANCE.getDirective(),
                ReverseLinkDirective.INSTANCE.getDirective()
        ));
        // graphql-java no longer builds a schema that has a directive on an element and no definition of it, so
        // the other directives the schema file defines stay defined.
        for (GraphQLDirective directive : schema.getDirectives()) {
            if (!Directives.isBuiltInDirective(directive)
                    && directives.stream().noneMatch(defined -> defined.getName().equals(directive.getName()))) {
                directives.add(directive);
            }
        }

        // Resolve references before using RegistrySetupVisitor
        log.info("Resolving type references");
        GraphQLTypeResolvingVisitor typeResolvingVisitor = new GraphQLTypeResolvingVisitor(typeMap);
        TRAVERSER.depthFirst(typeResolvingVisitor, typeMap.values());

        RegistrySetupVisitor fetcherSetupVisitor = new RegistrySetupVisitor(persistence, namespace, searchIndex);
        TRAVERSER.depthFirst(fetcherSetupVisitor, typeMap.values());

        GraphQLNamedType queryType = typeMap.remove(QUERY_NAME);

        return GraphQLSchema.newSchema()
                .query((GraphQLObjectType) queryType)
                .additionalTypes(new HashSet<>(typeMap.values()))
                .additionalDirectives(directives)
                .codeRegistry(fetcherSetupVisitor.getRegistry())
                .build();
    }
}
