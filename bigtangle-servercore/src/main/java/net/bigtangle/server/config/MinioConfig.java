package net.bigtangle.server.config;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;

@Configuration
public class MinioConfig {

	@Value("${minio.url:http://minio:9000}")
	private String minioUrl;

	@Value("${minio.accessKey:minioadmin}")
	private String minioAccessKey;

	@Value("${minio.secretKey:minioadminpassword}")
	private String minioSecretKey;

	@Value("${minio.bucketName:bigtangle}")
	private String bucketName;

	
	@Bean
	public MinioClient minioClient() throws InvalidKeyException, ErrorResponseException, InsufficientDataException,
			InternalException, InvalidResponseException, NoSuchAlgorithmException, ServerException, XmlParserException,
			IllegalArgumentException, IOException {
		MinioClient minioClient = MinioClient.builder().endpoint(minioUrl).credentials(minioAccessKey, minioSecretKey)
				.build(); 
		boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
		if (!found) {
			minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
		} else {
			System.out.println("Bucket 'bigtangle' already exists.");
		}
		return minioClient;
	}


	public String getMinioUrl() {
		return minioUrl;
	}


	public void setMinioUrl(String minioUrl) {
		this.minioUrl = minioUrl;
	}


	public String getMinioAccessKey() {
		return minioAccessKey;
	}


	public void setMinioAccessKey(String minioAccessKey) {
		this.minioAccessKey = minioAccessKey;
	}


	public String getMinioSecretKey() {
		return minioSecretKey;
	}


	public void setMinioSecretKey(String minioSecretKey) {
		this.minioSecretKey = minioSecretKey;
	}


	public String getBucketName() {
		return bucketName;
	}


	public void setBucketName(String bucketName) {
		this.bucketName = bucketName;
	}
	
	
}
