package io.descoped.lds.api.persistence.flattened;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.streaming.Fragment;
import io.descoped.lds.api.persistence.streaming.FragmentType;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public class FlattenedDocumentLeafNode {

    private static final byte[] EMPTY = new byte[0];
    private static final byte[] TRUE = {(byte) 1};
    private static final byte[] FALSE = {(byte) 0};

    private final DocumentKey key;
    private final String path;
    private final FragmentType type;
    private final String value;
    private final int capacity;

    public FlattenedDocumentLeafNode(DocumentKey key, String path, FragmentType type, String value, int capacity) {
        this.key = key;
        this.path = path;
        this.type = type;
        this.value = value;
        this.capacity = capacity;
    }

    public DocumentKey key() {
        return key;
    }

    public String path() {
        return path;
    }

    public FragmentType type() {
        return type;
    }

    public Object value() {
        return value;
    }

    public static Map<Integer, byte[]> valueByOffset(FragmentType type, int fragmentCapacity, String value) {
        Map<Integer, byte[]> valueByOffset = new TreeMap<>();
        switch (type) {
            case NULL, EMPTY_OBJECT, EMPTY_ARRAY -> valueByOffset.put(0, EMPTY);
            case BOOLEAN -> valueByOffset.put(0, Boolean.parseBoolean(value) ? TRUE : FALSE);
            case NUMERIC -> valueByOffset.put(0, value.getBytes(StandardCharsets.UTF_8));
            case STRING -> putString(valueByOffset, fragmentCapacity, value);
            case null, default -> throw new IllegalStateException("Unknown FragmentType: " + type);
        }
        return valueByOffset;
    }

    /*
     * The UTF-8 bytes of a text, cut in pieces of at most the fragment capacity, by the offset of each piece.
     */
    private static void putString(Map<Integer, byte[]> valueByOffset, int fragmentCapacity, String value) {
        // A char is at most three bytes in UTF-8, so a value is cut only where it exceeds the fragment capacity.
        ByteBuffer out = ByteBuffer.allocate((int) Math.min(fragmentCapacity, 3L * value.length() + 256));
        CharBuffer in = CharBuffer.wrap(value);
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        CoderResult coderResult;
        coderResult = encoder.encode(in, out, false);
        handleError(coderResult);
        int offset = 0;
        while (coderResult.isOverflow()) {
            byte[] fragmentValue = new byte[out.position()];
            System.arraycopy(out.array(), 0, fragmentValue, 0, out.position());
            valueByOffset.put(offset, fragmentValue);
            offset += out.position();
            out.clear();
            coderResult = encoder.encode(in, out, false);
            handleError(coderResult);
        }
        // underflow
        CoderResult endOfInputCoderResult = encoder.encode(in, out, true);
        handleError(endOfInputCoderResult);
        CoderResult flushCoderResult = encoder.flush(out);
        handleError(flushCoderResult);
        byte[] fragmentValue = new byte[out.position()];
        System.arraycopy(out.array(), 0, fragmentValue, 0, out.position());
        valueByOffset.put(offset, fragmentValue);
    }

    Iterator<Fragment> fragmentIterator() {
        Map<Integer, byte[]> valueByOffset = valueByOffset(type, capacity, value);
        List<Fragment> fragments = new LinkedList<>();
        for (Map.Entry<Integer, byte[]> entry : valueByOffset.entrySet()) {
            fragments.add(new Fragment(key, path, type, entry.getKey(), entry.getValue()));
        }
        return fragments.iterator();
    }

    static void handleError(CoderResult coderResult) {
        if (coderResult.isError()) {
            try {
                coderResult.throwException();
                throw new IllegalStateException();
            } catch (CharacterCodingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public String toString() {
        return "FlattenedDocumentLeafNode{" +
                "path='" + path + '\'' +
                ", value='" + value + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlattenedDocumentLeafNode that = (FlattenedDocumentLeafNode) o;
        return Objects.equals(path, that.path) &&
                Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, value);
    }
}
