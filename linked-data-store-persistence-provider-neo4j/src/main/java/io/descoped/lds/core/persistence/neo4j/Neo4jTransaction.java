package io.descoped.lds.core.persistence.neo4j;

import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.TransactionStatistics;
import io.reactivex.rxjava3.core.Flowable;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.temporal.Temporal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One transaction of the driver in a session of its own. The session is closed when the transaction ends, so its
 * connection goes back to the driver's pool.
 */
final class Neo4jTransaction implements Transaction {

    private static final Logger LOG = LoggerFactory.getLogger(Neo4jTransaction.class);

    private final boolean logCypher;
    private final Session session;
    private final org.neo4j.driver.Transaction neo4jTransaction;
    private final TransactionStatistics statistics = new TransactionStatistics();
    private final CompletableFuture<TransactionStatistics> result;

    Neo4jTransaction(Session session, boolean logCypher) {
        this.logCypher = logCypher;
        this.session = session;
        this.neo4jTransaction = session.beginTransaction();
        this.result = new CompletableFuture<>();
    }

    @Override
    public CompletableFuture<TransactionStatistics> commit() {
        if (!result.isDone()) {
            end(true);
        }
        return result;
    }

    @Override
    public CompletableFuture<TransactionStatistics> cancel() {
        if (!result.isDone()) {
            end(false);
        }
        return result;
    }

    private void end(boolean commit) {
        // Whatever is thrown here is caught, an Error too: the future is what the caller waits for, and it is
        // completed in every case.
        Throwable failure = null;
        try {
            if (commit) {
                neo4jTransaction.commit();
            } else {
                neo4jTransaction.rollback();
            }
        } catch (Throwable t) {
            failure = t;
        }
        try {
            session.close();
        } catch (Throwable t) {
            if (failure == null) {
                failure = t;
            } else {
                failure.addSuppressed(t);
            }
        }
        if (failure == null) {
            result.complete(statistics);
        } else {
            result.completeExceptionally(failure);
        }
    }

    /**
     * Runs a statement and returns its result, to be read by the caller before the next statement is run.
     */
    Result executeCypher(String query, Map<String, Object> parameters) {
        log(query, parameters);
        return neo4jTransaction.run(query, parameters);
    }

    /**
     * A stream of the records of a statement. The statement is run when the stream is subscribed to, and a record
     * is taken from the driver for each one that is asked for; the driver fetches them from the server in
     * batches. What is left when the stream is cancelled is discarded on the server.
     */
    Flowable<Record> executeCypherAsync(String query, Map<String, Object> parameters) {
        return Flowable.generate(
                () -> executeCypher(query, parameters),
                (rows, emitter) -> {
                    if (rows.hasNext()) {
                        emitter.onNext(rows.next());
                    } else {
                        emitter.onComplete();
                    }
                },
                Result::consume);
    }

    private void log(String query, Map<String, Object> parameters) {
        if (logCypher && LOG.isInfoEnabled()) {
            LOG.info(":: CYPHER ::\n{}", interactive(query, parameters));
        }
    }

    /**
     * The statement with its parameters written into it, to be read or run by hand.
     */
    static String interactive(String query, Map<String, Object> parameters) {
        String interactiveQuery = query;
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            // the whole name: $id is not the beginning of $idAfter
            String regex = "\\$" + Pattern.quote(entry.getKey()) + "\\b";
            String replacement = serializeForInteractive(entry.getValue());
            interactiveQuery = interactiveQuery.replaceAll(regex, Matcher.quoteReplacement(replacement));
        }
        return interactiveQuery;
    }

    private static String serializeForInteractive(Object parameters) {
        StringBuilder sb = new StringBuilder();
        doSerializeRecursively(sb, parameters);
        return sb.toString();
    }

    private static void doSerializeRecursively(StringBuilder sb, Object input) {
        switch (input) {
            case null -> sb.append("NULL");
            case Map<?, ?> map -> {
                sb.append("{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    sb.append(entry.getKey()).append(": ");
                    doSerializeRecursively(sb, entry.getValue());
                }
                sb.append("}");
            }
            case List<?> list -> {
                sb.append("[");
                boolean first = true;
                for (Object o : list) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    doSerializeRecursively(sb, o);
                }
                sb.append("]");
            }
            case CharSequence text -> sb.append("'").append(text).append("'");
            case Number number -> sb.append(number);
            case Temporal temporal -> sb.append("datetime('").append(temporal).append("')");
            default -> {
                // other types were never written out
            }
        }
    }
}
