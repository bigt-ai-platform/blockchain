package net.bigtangle.server.service.base;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import net.bigtangle.core.Block;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.server.config.MinioConfig;
import net.bigtangle.utils.Gzip;

/**
 * <p>
 * Provides services for object store s3 compatible.
 * </p>
 */

public class MinioService {

	protected MinioConfig minioConfig;

	protected NetworkParameters networkParameters;
	private static final Logger logger = LoggerFactory.getLogger(MinioService.class);

	public MinioService(MinioConfig minioConfig, NetworkParameters networkParameters) {
		super();
		this.minioConfig = minioConfig;
		this.networkParameters = networkParameters;
	}

	public MinioClient minioClient() throws InvalidKeyException, ErrorResponseException, InsufficientDataException,
			InternalException, InvalidResponseException, NoSuchAlgorithmException, ServerException, XmlParserException,
			IllegalArgumentException, IOException {
		MinioClient minioClient = MinioClient.builder().endpoint(minioConfig.getMinioUrl())
				.credentials(minioConfig.getMinioAccessKey(), minioConfig.getMinioSecretKey())
				.build();
		return minioClient;
	}

	public void put(Block block) throws BlockStoreException {
		String objectName = block.getHash().toString();
		try {
			byte[] compressedData = Gzip.compress(block.bitcoinSerialize());
			try (java.io.ByteArrayInputStream is = new java.io.ByteArrayInputStream(compressedData)) {

				minioClient().putObject(PutObjectArgs.builder().bucket(minioConfig.getBucketName()).object(objectName)
						.stream(is, compressedData.length, -1).contentType("application/octet-stream").build());

			//	logger.debug("Block " + objectName + " is successfully uploaded to Minio.");
			}
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}

	public Block get(Sha256Hash blockhash) throws BlockStoreException {
		if (networkParameters.getGenesisBlock().getHash().equals(blockhash))
			return networkParameters.getGenesisBlock();
		try (java.io.InputStream retrievedIs = minioClient().getObject(
				GetObjectArgs.builder().bucket(minioConfig.getBucketName()).object(blockhash.toString()).build())) {

			return networkParameters.getDefaultSerializer().makeZippedBlockStream(retrievedIs);
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}

	public byte[] getByte(Sha256Hash blockhash) throws BlockStoreException {
		if (networkParameters.getGenesisBlock().getHash().equals(blockhash))
			return Gzip.compress(networkParameters.getGenesisBlock().bitcoinSerialize()); // Read the object back from
																							// Minio
		try (java.io.InputStream retrievedIs = minioClient().getObject(
				GetObjectArgs.builder().bucket(minioConfig.getBucketName()).object(blockhash.toString()).build())) {

			return retrievedIs.readAllBytes();

		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}
}
