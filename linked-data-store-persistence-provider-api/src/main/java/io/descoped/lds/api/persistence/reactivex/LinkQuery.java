package io.descoped.lds.api.persistence.reactivex;

import io.descoped.lds.api.json.JsonNavigationPath;

/**
 * The documents at the other end of a link, asked for from the document that is known.
 * <p>
 * Given to {@link RxJsonPersistence#readTargetDocuments} with the source document as the known document, and to
 * {@link RxJsonPersistence#readSourceDocuments} with the target document as the known document.
 *
 * @param namespace    the namespace of the document that is known
 * @param entity       the entity type of the document that is known
 * @param id           the id of the document that is known
 * @param relationPath the path of the link field in the source document
 * @param otherEntity  the entity type of the documents asked for
 */
public record LinkQuery(String namespace, String entity, String id, JsonNavigationPath relationPath,
                        String otherEntity) {
}
