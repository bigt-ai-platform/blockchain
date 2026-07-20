package net.bigtangle.wallet;

import java.util.Arrays;

final class ByteArrayKey {
    final byte[] data;
    ByteArrayKey(byte[] data) { this.data = data; }
    @Override public boolean equals(Object o) {
        if (!(o instanceof ByteArrayKey)) return false;
        return Arrays.equals(data, ((ByteArrayKey) o).data);
    }
    @Override public int hashCode() { return Arrays.hashCode(data); }
}
