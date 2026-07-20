package net.bigtangle.crypto;

import java.util.Arrays;
import java.util.Objects;

public class ScryptParameters {

    private final byte[] salt;
    private final int n;
    private final int r;
    private final int p;

    public ScryptParameters(byte[] salt, int n, int r, int p) {
        this.salt = salt != null ? salt.clone() : null;
        this.n = n;
        this.r = r;
        this.p = p;
    }

    public ScryptParameters(byte[] salt) {
        this(salt, 0, 0, 0);
    }

    public byte[] getSalt() {
        return salt != null ? salt.clone() : null;
    }

    public int getN() {
        return n;
    }

    public int getR() {
        return r;
    }

    public int getP() {
        return p;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScryptParameters that = (ScryptParameters) o;
        return n == that.n && r == that.r && p == that.p && Arrays.equals(salt, that.salt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(n, r, p, Arrays.hashCode(salt));
    }
}
