package io.descoped.lds.api.persistence.flattened;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.streaming.Fragment;
import io.descoped.lds.api.persistence.streaming.FragmentType;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static java.util.Optional.ofNullable;

public class FlattenedDocument {

    /**
     * Paths with index need to be sorted lexicographically on the path but numerically on the indices.
     */
    private static Comparator<String> INDEX_AWARE_PATH_COMPARATOR = new PathComparator();

    private final DocumentKey key;
    private final Map<String, FlattenedDocumentLeafNode> leafNodesByPath;
    private final boolean deleted;

    public FlattenedDocument(DocumentKey key, Map<String, FlattenedDocumentLeafNode> leafNodesByPath, boolean deleted) {
        this.key = key;
        this.leafNodesByPath = new TreeMap<>(INDEX_AWARE_PATH_COMPARATOR);
        this.leafNodesByPath.putAll(leafNodesByPath);
        this.deleted = deleted;
    }

    public DocumentKey key() {
        return key;
    }

    public Map<String, FlattenedDocumentLeafNode> leafNodesByPath() {
        return leafNodesByPath;
    }

    public FlattenedDocumentLeafNode leaf(String path) {
        return leafNodesByPath.get(path);
    }

    public boolean deleted() {
        return deleted;
    }

    public boolean contains(String path, String value) {
        return ofNullable(leafNodesByPath.get(path)).map(leaf -> leaf.value()).map(v -> value.equals(v)).orElse(Boolean.FALSE);
    }

    @Override
    public String toString() {
        return "FlattenedDocument{" +
                "key=" + key +
                ", fragmentByPath=" + leafNodesByPath +
                ", deleted=" + deleted +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlattenedDocument document = (FlattenedDocument) o;
        return deleted == document.deleted &&
                Objects.equals(key, document.key) &&
                Objects.equals(leafNodesByPath, document.leafNodesByPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, leafNodesByPath, deleted);
    }

    public Iterator<Fragment> fragmentIterator() {
        // The fragments are collected in a list first, not made as the iterator is asked for them.
        List<Fragment> allDocumentFragments = new LinkedList<>();
        for (Map.Entry<String, FlattenedDocumentLeafNode> entry : leafNodesByPath.entrySet()) {
            Iterator<Fragment> fragmentIterator = entry.getValue().fragmentIterator();
            while (fragmentIterator.hasNext()) {
                Fragment fragment = fragmentIterator.next();
                allDocumentFragments.add(fragment);
            }
        }
        return allDocumentFragments.iterator();
    }

    public static FlattenedDocument decodeDocument(DocumentKey documentKey, Map<String, ? extends Collection<Fragment>> fragmentsByPath, int fragmentValueCapacityBytes) {
        TreeMap<String, FlattenedDocumentLeafNode> leafNodesByPath = new TreeMap<>();
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        CharBuffer out = CharBuffer.allocate(256);
        boolean deleted = false;
        for (Map.Entry<String, ? extends Collection<Fragment>> entry : fragmentsByPath.entrySet()) {
            String path = entry.getKey();
            List<Fragment> fragments = new ArrayList<>(entry.getValue());
            if (fragments.isEmpty()) {
                throw new IllegalStateException("No fragments for path: " + path);
            }
            FragmentType fragmentType = fragments.get(0).fragmentType();
            switch (fragmentType) {
                case STRING -> {
                    String value = decodeString(fragments, decoder, out);
                    if (value == null) {
                        return new FlattenedDocument(documentKey, Collections.emptyMap(), true);
                    }
                    leafNodesByPath.put(path, new FlattenedDocumentLeafNode(documentKey, path, FragmentType.STRING, value, fragmentValueCapacityBytes));
                }
                case NUMERIC -> {
                    byte[] value = fragments.get(0).value();
                    leafNodesByPath.put(path, new FlattenedDocumentLeafNode(documentKey, path, FragmentType.NUMERIC, new String(value, StandardCharsets.UTF_8), fragmentValueCapacityBytes));
                }
                case BOOLEAN -> {
                    byte[] byteValue = fragments.get(0).value();
                    String value = (byteValue[0] == (byte) 1) ? "true" : "false";
                    leafNodesByPath.put(path, new FlattenedDocumentLeafNode(documentKey, path, FragmentType.BOOLEAN, value, fragmentValueCapacityBytes));
                }
                case NULL, EMPTY_ARRAY, EMPTY_OBJECT ->
                        leafNodesByPath.put(path, new FlattenedDocumentLeafNode(documentKey, path, fragmentType, null, fragmentValueCapacityBytes));
                case DELETED -> deleted = true;
                case null, default -> throw new IllegalStateException("Unknown FragmentType: " + fragmentType);
            }
        }
        return new FlattenedDocument(documentKey, leafNodesByPath, deleted);
    }

    /*
     * The text that the fragments of one leaf hold, or null when one of them is a delete marker.
     */
    private static String decodeString(List<Fragment> fragments, CharsetDecoder decoder, CharBuffer out) {
        StringBuilder value = new StringBuilder();
        decoder.reset();
        out.clear();
        ByteBuffer in = null;
        for (Fragment fragment : fragments) {
            if (fragment.deleteMarker()) {
                return null;
            }
            in = ByteBuffer.wrap(fragment.value());
            CoderResult coderResult = decoder.decode(in, out, false);
            throwRuntimeExceptionIfError(coderResult);
            while (coderResult.isOverflow()) {
                // drain out buffer
                value.append(out.flip());
                out.clear();
                coderResult = decoder.decode(in, out, false);
                throwRuntimeExceptionIfError(coderResult);
            }
            // underflow but possibly more fragments in leaf-node
        }
        // underflow and all fragments decoded
        CoderResult endOfInputCoderResult = decoder.decode(in, out, true);
        throwRuntimeExceptionIfError(endOfInputCoderResult);
        CoderResult flushCoderResult = decoder.flush(out);
        throwRuntimeExceptionIfError(flushCoderResult);
        value.append(out.flip());
        return value.toString();
    }

    private static void throwRuntimeExceptionIfError(CoderResult coderResult) {
        if (coderResult.isError()) {
            try {
                coderResult.throwException();
                throw new IllegalStateException();
            } catch (CharacterCodingException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
