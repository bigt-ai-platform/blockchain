package net.bigtangle.server.service.base;

import java.util.concurrent.TimeUnit;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.MinioConfig;
import net.bigtangle.utils.Gzip;
import okhttp3.OkHttpClient;

/**
 * <p>
 * Provides services for object store s3 compatible.
 * </p>
 */

public class MinioService {

	protected MinioConfig minioConfig;

	protected NetworkParameters networkParameters;
	protected MinioClient minioClient;
	//private static final Logger logger = LoggerFactory.getLogger(MinioService.class);

	public MinioService(MinioConfig minioConfig, NetworkParameters networkParameters) {
		this.minioConfig = minioConfig;
		this.networkParameters = networkParameters;
		this.minioClient = MinioClient.builder().endpoint(minioConfig.getMinioUrl())
				.credentials(minioConfig.getMinioAccessKey(), minioConfig.getMinioSecretKey())
				.httpClient(new OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS) // Connection timeout
						.readTimeout(60, TimeUnit.SECONDS) // Read timeout
						.writeTimeout(60, TimeUnit.SECONDS) // Write timeout
						.build())
				.build();
	}

	public void put(Block block) throws BlockStoreException {
		String objectName = block.getHash().toString();
		try {
			byte[] compressedData = Gzip.compress(block.bitcoinSerialize());
			try (java.io.ByteArrayInputStream is = new java.io.ByteArrayInputStream(compressedData)) {

				minioClient.putObject(PutObjectArgs.builder().bucket(minioConfig.getBucketName()).object(objectName)
						.stream(is, compressedData.length, -1).contentType("application/octet-stream").build());

				// logger.debug("Block " + objectName + " is successfully uploaded to Minio.");
			}
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}

	public Block get(Sha256Hash blockhash) throws BlockStoreException {

		try (java.io.InputStream retrievedIs = minioClient.getObject(
				GetObjectArgs.builder().bucket(minioConfig.getBucketName()).object(blockhash.toString()).build())) {

			return networkParameters.getDefaultSerializer().makeZippedBlockStream(retrievedIs);
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}

	public byte[] getByte(Sha256Hash blockhash) throws BlockStoreException {
		// Minio
		try (java.io.InputStream retrievedIs = minioClient.getObject(
				GetObjectArgs.builder().bucket(minioConfig.getBucketName()).object(blockhash.toString()).build())) {

			return retrievedIs.readAllBytes();

		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}
}
