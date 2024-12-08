package net.bigtangle.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.io.IOUtils;

public class Gzip {
 
    public static byte[] decompressOut(byte[] contentBytes) throws IOException {
        if (contentBytes.length == 0)
            return new byte[0];
        ByteArrayInputStream bis = new ByteArrayInputStream(contentBytes);
        GZIPInputStream gis = new GZIPInputStream(bis);
        return IOUtils.toByteArray(gis);
    }

    public static byte[] compress(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
        try (GZIPOutputStream out = new GZIPOutputStream(bos)) {
            out.write(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();

    }
}
