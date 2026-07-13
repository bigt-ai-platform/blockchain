package net.bigtangle.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Gzip {

	private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
	private static final int BUFFER_SIZE = 8192;

	/** GZIP magic bytes: 0x1f 0x8b */
	private static final int GZIP_MAGIC = 0x1f8b;

	/**
	 * Decompresses gzip data if the input starts with gzip magic bytes,
	 * otherwise returns the raw bytes unchanged. This handles responses
	 * that may or may not be gzip-compressed depending on payload size.
	 */
	public static byte[] decompressOut(byte[] contentBytes) throws IOException {
		if (contentBytes.length == 0) {
			return EMPTY_BYTE_ARRAY;
		}
		// Check for gzip magic bytes
		if (contentBytes.length >= 2 && ((contentBytes[0] & 0xff) << 8 | (contentBytes[1] & 0xff)) == GZIP_MAGIC) {
			try (ByteArrayInputStream bis = new ByteArrayInputStream(contentBytes);
					GZIPInputStream gis = new GZIPInputStream(bis);
					ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

				byte[] buffer = new byte[BUFFER_SIZE];
				int bytesRead;
				while ((bytesRead = gis.read(buffer)) != -1) {
					baos.write(buffer, 0, bytesRead);
				}
				return baos.toByteArray();
			}
		}
		return contentBytes;
	}

	public static byte[] decompressOutStream(InputStream bis) throws IOException {

		try (GZIPInputStream gis = new GZIPInputStream(bis); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

			byte[] buffer = new byte[BUFFER_SIZE];
			int bytesRead;
			while ((bytesRead = gis.read(buffer)) != -1) {
				baos.write(buffer, 0, bytesRead);
			}
			return baos.toByteArray();
		}
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
