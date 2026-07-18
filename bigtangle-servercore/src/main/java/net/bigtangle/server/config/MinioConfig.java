package net.bigtangle.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

	@Value("${minio.url:}")
	private String minioUrl;

	@Value("${minio.accessKey:}")
	private String minioAccessKey;

	@Value("${minio.secretKey:}")
	private String minioSecretKey;

	@Value("${minio.bucketName:bigtangle}")
	private String bucketName;

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
