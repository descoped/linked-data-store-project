package io.descoped.lds.api.persistence.streaming;

public enum FragmentType {
    NULL((byte) 0), DELETED((byte) 1), EMPTY_OBJECT((byte) 2), EMPTY_ARRAY((byte) 3), STRING((byte) 4), NUMERIC((byte) 5), BOOLEAN((byte) 6);

    final byte typeCode;

    FragmentType(byte typeCode) {
        this.typeCode = typeCode;
    }

    public byte getTypeCode() {
        return typeCode;
    }

    public static FragmentType fromTypeCode(byte typeCode) {
        return switch (typeCode) {
            case 0 -> NULL;
            case 1 -> DELETED;
            case 2 -> EMPTY_OBJECT;
            case 3 -> EMPTY_ARRAY;
            case 4 -> STRING;
            case 5 -> NUMERIC;
            case 6 -> BOOLEAN;
            default -> throw new IllegalArgumentException("typeCode not supported: " + typeCode);
        };
    }
}
