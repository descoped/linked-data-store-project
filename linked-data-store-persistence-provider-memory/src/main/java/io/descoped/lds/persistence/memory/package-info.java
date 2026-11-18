/**
 * The persistence provider {@code mem}: documents in an ordered map in the JVM's memory.
 *
 * <p>It is the provider LDS's own tests run on and the quickest way to try LDS. It is not for production: nothing
 * survives the process, and everything has to fit in the heap. Transactions see committed data and their own
 * writes; when two transactions write the same version, the one that commits last wins.
 *
 * <p>Keys are tuples packed so that their bytes sort in key order ({@code io.descoped.tuple}):
 * <pre>
 * ("d", namespace, entity, id, -seconds, -microseconds)                     document content or delete marker
 * ("v", namespace, entity, path, hash, id, -seconds, -microseconds)         a string value at a path, for find
 * </pre>
 * The version is negated, so the newest version of a document comes first.
 */
package io.descoped.lds.persistence.memory;
