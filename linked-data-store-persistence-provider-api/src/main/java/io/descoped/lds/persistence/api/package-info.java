/**
 * The persistence service provider interface: versioned JSON documents, the links between them and transactions.
 *
 * <p>A provider implements {@link io.descoped.lds.persistence.api.PersistenceProvider} and
 * {@link io.descoped.lds.persistence.api.Persistence}, registers the former as a service, and passes the tests in
 * {@code linked-data-store-persistence-provider-tck}. Every call blocks until it is done; callers that want
 * concurrency run them on virtual threads.
 *
 * <h2>The rules every provider follows</h2>
 * <ul>
 *   <li><b>Versions</b> are UTC instants with microsecond precision ({@link
 *       io.descoped.lds.persistence.api.DocumentKey}). A snapshot sees, for each document, the newest version that is
 *       not after it.</li>
 *   <li><b>Delete markers</b> are versions without content. A snapshot that lands on one does not see the document;
 *       {@link io.descoped.lds.persistence.api.Persistence#versions versions} lists the marker.</li>
 *   <li><b>Namespaces</b> are isolated from each other.</li>
 *   <li><b>Order</b>: documents are ordered by id in Unicode code point order ({@link
 *       io.descoped.lds.persistence.api.Identifiers#ID_ORDER}), versions by time. A backward {@link
 *       io.descoped.lds.persistence.api.Range} returns the same order reversed.</li>
 *   <li><b>Names</b> are validated ({@link io.descoped.lds.persistence.api.Identifiers}) and never become part of a
 *       query text, a key or a path without it.</li>
 *   <li><b>Transactions</b> commit only when {@link io.descoped.lds.persistence.api.Transaction#commit() commit} is
 *       called; closing one that was not committed rolls it back.</li>
 *   <li><b>JSON</b> comes back as it went in: nested arrays, nulls, empty containers, any field name, and numbers
 *       with the value they had.</li>
 * </ul>
 */
package io.descoped.lds.persistence.api;
