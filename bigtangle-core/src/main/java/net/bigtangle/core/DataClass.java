package net.bigtangle.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Block may contains data with the dataClassName and the class has a version number
 */
public abstract class DataClass {
    private static final Logger log = LoggerFactory.getLogger(DataClass.class);
    private long version = 1L;

    public byte[] toByteArray() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeLong(version);
            
            dos.close();
        } catch (IOException e) {
            log.error("Failed to serialize DataClass", e);
        }
        return baos.toByteArray();
    }

    protected DataClass parseDIS(DataInputStream dis) throws IOException {
        version = dis.readLong();
        return this;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

	@Override
	public int hashCode() {
		return Objects.hash(version);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		DataClass other = (DataClass) obj;
		return version == other.version;
	}

}
