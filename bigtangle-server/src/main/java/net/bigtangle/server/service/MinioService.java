package net.bigtangle.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import net.bigtangle.core.Block;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.server.config.MinioConfig;

/**
 * <p>
 * Provides services for object store s3 compatible.
 * </p>
 */
@Service
public class MinioService {

	@Autowired
	protected MinioClient minioClient;

	@Autowired
	protected MinioConfig minioConfig;

	private static final Logger logger = LoggerFactory.getLogger(MinioService.class);

	public void put(Block block) throws BlockStoreException {
		String objectName =  block.getHash().toString();
		try {
			try (java.io.ByteArrayInputStream is = new java.io.ByteArrayInputStream(block.bitcoinSerialize())) {

				minioClient.putObject(PutObjectArgs.builder().bucket(minioConfig.getBucketName()).object(objectName)
						.stream(is, is.available(), -1).contentType("application/octet-stream").build());

				logger.debug("Block " + objectName + " is successfully uploaded to Minio.");
			}
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}

	public byte[] get(String blockhash) throws BlockStoreException {
		// Read the object back from Minio
		try (java.io.InputStream retrievedIs = minioClient.getObject(
				GetObjectArgs.builder()
						.bucket(minioConfig.getBucketName())
						.object(blockhash)
						.build())) {

			return retrievedIs.readAllBytes();

		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}
}
