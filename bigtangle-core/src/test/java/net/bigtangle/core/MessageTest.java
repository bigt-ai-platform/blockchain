/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.exception.ProtocolException;
import net.bigtangle.params.MainNetParams;

public class MessageTest {

	// If readStr() is vulnerable this causes OutOfMemory
	@Test
	public void readStrOfExtremeLength() throws Exception {
		assertThrows(ProtocolException.class, () -> {
			NetworkParameters params = MainNetParams.get();
			VarInt length = new VarInt(Integer.MAX_VALUE);
			byte[] payload = length.encode();
			new VarStrMessage(params, payload);
		});

	}

	static class VarStrMessage extends Message {
		public VarStrMessage(NetworkParameters params, byte[] payload) {
			super(params, payload, 0);
		}

		@Override
		protected void parse() throws ProtocolException {
			readStr();
		}
	}

	// If readBytes() is vulnerable this causes OutOfMemory
	@Test
	public void readByteArrayOfExtremeLength() throws Exception {
		assertThrows(ProtocolException.class, () -> {
			NetworkParameters params = MainNetParams.get();
			VarInt length = new VarInt(Integer.MAX_VALUE);
			byte[] payload = length.encode();
			new VarBytesMessage(params, payload);
		});

	}

	static class VarBytesMessage extends Message {
		public VarBytesMessage(NetworkParameters params, byte[] payload) {
			super(params, payload, 0);
		}

		@Override
		protected void parse() throws ProtocolException {
			readByteArray();
		}
	}
}
