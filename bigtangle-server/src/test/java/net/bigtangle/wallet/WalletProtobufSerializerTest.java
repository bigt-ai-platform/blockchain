/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.wallet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import net.bigtangle.core.Address;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.utils.BriefLogFormatter;
import net.bigtangle.wallet.Protos;
import net.bigtangle.wallet.UnreadableWalletException;
import net.bigtangle.wallet.Wallet;
import net.bigtangle.wallet.WalletProtobufSerializer;

public class WalletProtobufSerializerTest {
	private static final NetworkParameters PARAMS = MainNetParams.get();
	private ECKey myKey;
	private ECKey myWatchedKey;
	private Address myAddress;
	private Wallet myWallet;

	public static String WALLET_DESCRIPTION = "The quick brown fox lives in \u4f26\u6566"; // Beijing in Chinese
	private long mScriptCreationTime;

	@BeforeEach
	public void setUp() throws Exception {
		BriefLogFormatter.initVerbose();

		myWatchedKey = new ECKey();
		myWallet = Wallet.fromKeys(PARAMS, myWatchedKey);
		myKey = new ECKey();
		myKey.setCreationTimeSeconds(123456789L);
		myWallet.importKey(myKey);
		myAddress = myKey.toAddress(PARAMS);
		myWallet = Wallet.fromKeys(PARAMS, myKey);
		myWallet.importKey(myKey);
		mScriptCreationTime = new Date().getTime() / 1000 - 1234;

	}

	@Test
	public void empty() throws Exception {
		// Check the base case of a wallet with one key and no transactions.
		Wallet wallet1 = roundTrip(myWallet);

		assertArrayEquals(myKey.getPubKey(), wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPubKey());
		assertArrayEquals(myKey.getPrivKeyBytes(), wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPrivKeyBytes());
		assertEquals(myKey.getCreationTimeSeconds(),
				wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getCreationTimeSeconds());

	}

	@Test
	public void testKeys() throws Exception {
		for (int i = 0; i < 20; i++) {
			myKey = new ECKey();
			myAddress = myKey.toAddress(PARAMS);
			myWallet = Wallet.fromKeys(PARAMS, myKey);

			Wallet wallet1 = roundTrip(myWallet);
			assertArrayEquals(myKey.getPubKey(), wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPubKey());
			assertArrayEquals(myKey.getPrivKeyBytes(),
					wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPrivKeyBytes());
		}
	}

	// @Test
	public void testAppearedAtChainHeightDepthAndWorkDone() throws Exception {
	}

	private static Wallet roundTrip(Wallet wallet) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new WalletProtobufSerializer().writeWallet(wallet, output);
		ByteArrayInputStream test = new ByteArrayInputStream(output.toByteArray());
		assertTrue(WalletProtobufSerializer.isWallet(test));
		ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
		return new WalletProtobufSerializer().readWallet(input);
	}

	@Test
	public void testRoundTripNormalWallet() throws Exception {
		Wallet wallet1 = roundTrip(myWallet);

		assertArrayEquals(myKey.getPubKey(), wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPubKey());
		assertArrayEquals(myKey.getPrivKeyBytes(), wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPrivKeyBytes());
		assertEquals(myKey.getCreationTimeSeconds(),
				wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getCreationTimeSeconds());
	}

	// NoTag @Test
	public void tags() throws Exception {
		myWallet.setTag("foo", ByteString.copyFromUtf8("bar"));
		assertEquals("bar", myWallet.getTag("foo").toStringUtf8());
		myWallet = roundTrip(myWallet);
		assertEquals("bar", myWallet.getTag("foo").toStringUtf8());
	}

	@Test
	public void versions() throws Exception {
		assertThrows(UnreadableWalletException.FutureVersion.class, () -> {
			Protos.Wallet.Builder proto = Protos.Wallet
					.newBuilder(new WalletProtobufSerializer().walletToProto(myWallet));
			proto.setVersion(2);
			new WalletProtobufSerializer().readWallet(PARAMS, null, proto.build());
		});

	}
}
