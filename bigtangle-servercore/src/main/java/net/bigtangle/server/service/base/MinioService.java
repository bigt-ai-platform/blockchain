package net.bigtangle.server.service.base;

import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.exception.BlockStoreException;

/**
 * No-op MinioService. All methods are empty — MinIO object storage
 * is disabled. Block data is stored exclusively in the database.
 */
public class MinioService {

	public MinioService() {
	}

	public void put(Block block) throws BlockStoreException {
	}

	public Block get(Sha256Hash blockhash) throws BlockStoreException {
		return null;
	}

	public byte[] getByte(Sha256Hash blockhash) throws BlockStoreException {
		return null;
	}
}
