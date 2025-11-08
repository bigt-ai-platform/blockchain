/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.spongycastle.crypto.InvalidCipherTextException;

import net.bigtangle.apps.data.IdentityCore;
import net.bigtangle.apps.data.IdentityData;
import net.bigtangle.apps.data.SignedData;
import net.bigtangle.crypto.ECIESCoder;
import net.bigtangle.params.NetworkParameters;

public class SerializationTest {

	protected Sha256Hash getRandomSha256Hash() {
		byte[] rawHashBytes = new byte[32];
		new Random().nextBytes(rawHashBytes);
		Sha256Hash sha256Hash = Sha256Hash.wrap(rawHashBytes);
		return sha256Hash;
	}

	@Test
	public void testContactInfoSerialization() throws IOException {
		ContactInfo info1 = new ContactInfo();
		info1.setVersion(3);
		final Contact e = new Contact();
		e.setAddress("test1");
		e.setName("test2");
		info1.getContactList().add(e);
		ContactInfo info2 = new ContactInfo().parse(info1.toByteArray());

		assertArrayEquals(info1.toByteArray(), info2.toByteArray());
		assertEquals(info1.getVersion(), info2.getVersion());
		assertEquals(info1.getContactList().get(0).getAddress(), info2.getContactList().get(0).getAddress());
		assertEquals(info1.getContactList().get(0).getName(), info2.getContactList().get(0).getName());
	}

	@Test
	public void testContactInfo2Serialization() throws IOException {
		ContactInfo info1 = new ContactInfo();
		info1.setVersion(3);
		final Contact e = new Contact();
		e.setAddress("test1");
		e.setName(null);
		info1.getContactList().add(e);
		ContactInfo info2 = new ContactInfo().parse(info1.toByteArray());

		assertArrayEquals(info1.toByteArray(), info2.toByteArray());
		assertEquals(info1.getVersion(), info2.getVersion());
		assertEquals(info1.getContactList().get(0).getAddress(), info2.getContactList().get(0).getAddress());
		assertEquals(info1.getContactList().get(0).getName(), info2.getContactList().get(0).getName());
	}

	@Test
	public void testOrderOpenInfoSerialization() throws IOException {
		OrderOpenInfo info1 = new OrderOpenInfo(2l, "test1", new byte[] { 2 }, 3l, 4l, Side.SELL, "test2",
				NetworkParameters.BIGTANGLE_TOKENID_STRING, 1l, 3, NetworkParameters.BIGTANGLE_TOKENID_STRING);
		OrderOpenInfo info2 = new OrderOpenInfo().parse(info1.toByteArray());

		assertArrayEquals(info1.toByteArray(), info2.toByteArray());
		assertEquals(info1.getBeneficiaryAddress(), info2.getBeneficiaryAddress());
		assertArrayEquals(info1.getBeneficiaryPubKey(), info2.getBeneficiaryPubKey());
		assertEquals(info1.getTargetTokenid(), info2.getTargetTokenid());
		assertEquals(info1.getTargetValue(), info2.getTargetValue());
		assertEquals(info1.getValidFromTime(), info2.getValidFromTime());
		assertEquals(info1.getValidToTime(), info2.getValidToTime());
		assertEquals(info1.getVersion(), info2.getVersion());
	}

	@Test
	public void testContractEventInfoSerialization() throws IOException {
		ContractEventInfo info1 = new ContractEventInfo("contracttokenid", new BigInteger("1"), "tokenid", "address",
				3l, 4l, "");
		ContractEventInfo info2 = new ContractEventInfo().parse(info1.toByteArray());

		assertArrayEquals(info1.toByteArray(), info2.toByteArray());
		assertEquals(info1.getBeneficiaryAddress(), info2.getBeneficiaryAddress());
	 
		assertEquals(info1.getOfferValue(), info2.getOfferValue());
		assertEquals(info1.getOfferTokenid(), info2.getOfferTokenid());
		assertEquals(info1.getOfferSystem(), info2.getOfferSystem());
		assertEquals(info1.getContractTokenid(), info2.getContractTokenid());
		assertEquals(info1.getVersion(), info2.getVersion());
	}

	@Test
	public void testOrderCancelInfoSerialization() throws IOException {
		OrderCancelInfo info1 = new OrderCancelInfo(getRandomSha256Hash());
		OrderCancelInfo info2 = new OrderCancelInfo().parse(info1.toByteArray());

		assertArrayEquals(info1.toByteArray(), info2.toByteArray());
		assertEquals(info1.getBlockHash(), info2.getBlockHash());
	}

	@Test
	public void testMyHomeAddressSerialization() throws IOException {
		MyHomeAddress info1 = new MyHomeAddress();
		info1.setCity("test1");
		info1.setCountry("test2");
		info1.setEmail("test3");
		info1.setProvince("test4");
		info1.setRemark("test5");
		info1.setStreet("test6");
		MyHomeAddress info2 = new MyHomeAddress().parse(info1.toByteArray());

		assertArrayEquals(info1.toByteArray(), info2.toByteArray());
		assertEquals(info1.getCity(), info2.getCity());
		assertEquals(info1.getCountry(), info2.getCountry());
		assertEquals(info1.getEmail(), info2.getEmail());
		assertEquals(info1.getProvince(), info2.getProvince());
		assertEquals(info1.getRemark(), info2.getRemark());
		assertEquals(info1.getStreet(), info2.getStreet());
	}

	@Test
	public void testRewardInfoSerialization() throws IOException {
		Sha256Hash randomHash = getRandomSha256Hash();
		HashSet<Sha256Hash> blocks = new HashSet<Sha256Hash>();
		blocks.add(randomHash);
		RewardInfo info1 = new RewardInfo(randomHash, 2, blocks, 2l);
		byte[] bytes1 = info1.toByteArray();
		RewardInfo info2 = new RewardInfo().parse(bytes1);
		byte[] bytes2 = info2.toByteArray();

		assertArrayEquals(bytes1, bytes2);
		assertEquals(info1.getPrevRewardHash(), info2.getPrevRewardHash());
		assertEquals(info1.getChainlength(), info2.getChainlength());
		assertEquals(info1.getBlocks().toArray()[0], info2.getBlocks().toArray()[0]);
	}

	@Test
	public void testTokenInfoSerialization() throws IOException {
		List<MultiSignAddress> addresses = new ArrayList<>();
		Token tokens = Token.buildSimpleTokenInfo(true, null, "2", "3", "4", 2, 3, BigInteger.valueOf(4), true, 0,
				"de");
		TokenInfo info1 = new TokenInfo();
		info1.setToken(tokens);
		info1.setMultiSignAddresses(addresses);
		byte[] bytes1 = info1.toByteArray();
		TokenInfo info2 = new TokenInfo().parse(bytes1);
		byte[] bytes2 = info2.toByteArray();

		assertArrayEquals(bytes1, bytes2);
		assertEquals(info1.getMultiSignAddresses().size(), info2.getMultiSignAddresses().size());
		assertEquals(info1.getToken().getAmount(), info2.getToken().getAmount());
		assertEquals(info1.getToken().getBlockHash(), info2.getToken().getBlockHash());
		assertEquals(info1.getToken().getDescription(), info2.getToken().getDescription());
		assertEquals(info1.getToken().getPrevblockhash(), info2.getToken().getPrevblockhash());
		assertEquals(info1.getToken().getSignnumber(), info2.getToken().getSignnumber());
		assertEquals(info1.getToken().getTokenid(), info2.getToken().getTokenid());
		assertEquals(info1.getToken().getTokenindex(), info2.getToken().getTokenindex());
		assertEquals(info1.getToken().getTokenname(), info2.getToken().getTokenname());
		assertEquals(info1.getToken().getTokentype(), info2.getToken().getTokentype());
		assertEquals(info1.getToken().getDomainName(), info2.getToken().getDomainName());
		assertEquals(info1.getToken().isConfirmed(), info2.getToken().isConfirmed());

		assertEquals(info1.getToken().isTokenstop(), info2.getToken().isTokenstop());
	}

	@Test
	public void testKeyValueSerialization() throws InvalidCipherTextException, IOException {
		KeyValue kv = new KeyValue();
		kv.setKey("identity");
		kv.setValue("value");
		byte[] bytes1 = kv.toByteArray();
		KeyValue k2 = new KeyValue().parse(bytes1);
		assertEquals(kv.getKey(), k2.getKey());
		assertEquals(kv.getValue(), k2.getValue());
	}

	@Test
	public void testKeyValueListSerialization() throws InvalidCipherTextException, IOException {

		KeyValueList kvs = new KeyValueList();

		byte[] first = "my first file".getBytes();
		KeyValue kv = new KeyValue();
		kv.setKey("myfirst");
		kv.setValue(Utils.HEX.encode(first));
		kvs.addKeyvalue(kv);
		kv = new KeyValue();
		kv.setKey("second.pdf");
		kv.setValue(Utils.HEX.encode("second.pdf".getBytes()));
		kvs.addKeyvalue(kv);
		KeyValueList id = new KeyValueList().parse(kvs.toByteArray());

		assertTrue(id.getKeyvalues().size() == 2);
	}

	@Test
	public void testIdentityCoreSerialization() throws InvalidCipherTextException, IOException, SignatureException {

		IdentityCore identityCore = new IdentityCore();
		identityCore.setSurname("zhang");
		identityCore.setForenames("san");
		identityCore.setSex("man");
		identityCore.setDateofissue("20200101");
		identityCore.setDateofexpiry("20201231");

		IdentityCore id = new IdentityCore().parse(identityCore.toByteArray());
		assertTrue(id.getDateofissue().equals("20200101"));

	}

	@Test
	public void testIdentityCoreDataSerialization() throws InvalidCipherTextException, IOException, SignatureException {

		IdentityCore identityCore = new IdentityCore();
		identityCore.setSurname("zhang");
		identityCore.setForenames("san");
		identityCore.setSex("man");
		identityCore.setDateofissue("20200101");
		identityCore.setDateofexpiry("20201231");
		IdentityData identityData = new IdentityData();
		identityData.setIdentityCore(identityCore);
		identityData.setIdentificationnumber("120123456789012345");
		identityCore.setDateofbirth("20201231");
		identityData.setPhoto("readFile".getBytes());
		identityData.setIdentityCore(identityCore);
		System.out.println(identityData.uniqueNameIdentity());
		IdentityData id = new IdentityData().parse(identityData.toByteArray());
		assertTrue(id.getIdentificationnumber().equals("120123456789012345"));
		assertTrue(identityData.uniqueNameIdentity().equals(identityData.uniqueNameIdentity()));
		IdentityData identityData2 = new IdentityData();
		identityData2.setIdentificationnumber("546120123456789012345");
		assertTrue(!identityData.uniqueNameIdentity().equals(identityData2.uniqueNameIdentity()));
		IdentityData identityData3 = new IdentityData();
		assertTrue(!identityData.uniqueNameIdentity().equals(identityData3.uniqueNameIdentity()));
		IdentityData identityData4 = new IdentityData();
		identityData2.setIdentificationnumber(null);
		assertTrue(identityData4.uniqueNameIdentity().equals(identityData4.uniqueNameIdentity()));
		assertTrue(identityData3.uniqueNameIdentity().equals(identityData4.uniqueNameIdentity()));

	}

	@Test
	public void testIdentitySerialization() throws InvalidCipherTextException, IOException, SignatureException {
		ECKey key = new ECKey();
		ECKey userkey = new ECKey();
		TokenKeyValues tokenKeyValues = new TokenKeyValues();
		SignedData identity = new SignedData();
		IdentityCore identityCore = new IdentityCore();
		identityCore.setSurname("zhang");
		identityCore.setForenames("san");
		identityCore.setSex("man");
		identityCore.setDateofissue("20200101");
		identityCore.setDateofexpiry("20201231");
		IdentityData identityData = new IdentityData();
		identityData.setIdentityCore(identityCore);
		identityData.setIdentificationnumber("120123456789012345");
		byte[] photo = "readFile".getBytes();

		// readFile(new File("F:\\img\\cc_aes1.jpg"));
		identityData.setPhoto(photo);
		identity.setSerializedData(identityData.toByteArray());

		identity.setSignerpubkey(key.getPubKey());
		identity.signMessage(key);

		identity.verify();

		identity.setValidtodate(System.currentTimeMillis());
		byte[] data = identity.toByteArray();

		byte[] cipher = ECIESCoder.encrypt(key.getPubKeyPoint(), data);
		KeyValue kv = new KeyValue();
		kv.setKey(key.getPublicKeyAsHex());
		kv.setValue(Utils.HEX.encode(cipher));
		tokenKeyValues.addKeyvalue(kv);
		byte[] cipher1 = ECIESCoder.encrypt(userkey.getPubKeyPoint(), data);
		kv = new KeyValue();
		kv.setKey(userkey.getPublicKeyAsHex());
		kv.setValue(Utils.HEX.encode(cipher1));
		tokenKeyValues.addKeyvalue(kv);

		for (KeyValue kvtemp : tokenKeyValues.getKeyvalues()) {
			if (kvtemp.getKey().equals(userkey.getPublicKeyAsHex())) {
				byte[] decryptedPayload = ECIESCoder.decrypt(userkey.getPrivKey(), Utils.HEX.decode(kvtemp.getValue()));
				SignedData reidentity = new SignedData().parse(decryptedPayload);
				IdentityData id = new IdentityData().parse(Utils.HEX.decode(reidentity.getSerializedData()));
				assertTrue(id.getIdentificationnumber().equals("120123456789012345"));
				identity.verify();

			}
		}

	}

	@Test
	public void testTokenInfoSerializationWithMultiSignAddresses() throws IOException {
		List<MultiSignAddress> addresses = new ArrayList<>();
		
		// Create some MultiSignAddress objects to test serialization
		MultiSignAddress multiSignAddr1 = new MultiSignAddress("token1", "address1", "pubKeyHex1", 1);
		multiSignAddr1.setPosIndex(0);
		multiSignAddr1.setBlockhash(getRandomSha256Hash());
		
		MultiSignAddress multiSignAddr2 = new MultiSignAddress("token2", "address2", "pubKeyHex2", 2);
		multiSignAddr2.setPosIndex(1);
		multiSignAddr2.setBlockhash(getRandomSha256Hash());
		
		addresses.add(multiSignAddr1);
		addresses.add(multiSignAddr2);
		
		Token tokens = Token.buildSimpleTokenInfo(
			true, // confirmed
			null, // prevblockhash
			"2", // tokenid
			"3", // tokenname
			"4", // description
			3, // signnumber
			2, // tokenindex
			BigInteger.valueOf(4), // amount
			true, // tokenstop
			0, // signnumber (this appears to be a duplicate parameter in original, keeping for consistency)
			null // language
		);
		TokenInfo info1 = new TokenInfo();
		info1.setToken(tokens);
		info1.setMultiSignAddresses(addresses);
		byte[] bytes1 = info1.toByteArray();
		
		// Test serialization/deserialization of the dynamically created object
		TokenInfo info2 = new TokenInfo().parse(bytes1);
		assertArrayEquals(bytes1, info2.toByteArray());
		assertEquals(info1.getMultiSignAddresses().size(), info2.getMultiSignAddresses().size());
		assertEquals(info1.getToken().getTokenid(), info2.getToken().getTokenid());
		assertEquals(info1.getToken().getTokenname(), info2.getToken().getTokenname());
		assertEquals(info1.getToken().getDescription(), info2.getToken().getDescription());
		assertEquals(info1.getToken().getAmount(), info2.getToken().getAmount());
		assertEquals(info1.getToken().getSignnumber(), info2.getToken().getSignnumber());
		assertEquals(info1.getToken().getTokenindex(), info2.getToken().getTokenindex());
		assertEquals(info1.getToken().isConfirmed(), info2.getToken().isConfirmed());
		assertEquals(info1.getToken().isTokenstop(), info2.getToken().isTokenstop());
		
		// Also test parsing from hex string and its serialization/deserialization
		String hexString = "7b2276657273696f6e223a312c22746f6b656e223a7b2276657273696f6e223a312c22626c6f636b48617368223a6e756c6c2c22636f6e6669726d6564223a747275652c227370656e74223a66616c73652c227370656e646572426c6f636b48617368223a6e756c6c2c2274696d65223a302c22746f6b656e696e646578223a322c22746f6b656e6e616d65223a2233222c226465736372697074696f6e223a2234222c22646f6d61696e4e616d65223a22222c22646f6d61696e4e616d65426c6f636b48617368223a6e756c6c2c227369676e6e756d626572223a332c22746f6b656e74797065223a302c22746f6b656e73746f70223a747275652c2270726576626c6f636b68617368223a6e756c6c2c22616d6f756e74223a2234222c22646563696d616c73223a302c22636c617373696669636174696f6e223a6e756c6c2c226c616e6775616765223a6e756c6c2c227265766f6b6564223a66616c73652c22746f6b656e4b657956616c756573223a6e756c6c2c22746f6b656e6964223a2232227d2c226d756c74695369676e416464726573736573223a5b7b22626c6f636b68617368223a7b226279746573223a5b3135322c3134362c37312c35382c3138302c37332c3231352c3232312c3139302c3231332c38332c3230362c3234332c3230302c38342c3136362c3234312c32312c38322c37352c33372c36382c3134372c3130302c352c3233372c302c3233382c3133392c38352c38342c3232375d7d2c22746f6b656e6964223a22746f6b656e31222c2261646472657373223a226164647265737331222c227075624b6579486578223a227075624b657948657831222c22706f73496e646578223a302c22746f6b656e486f6c646572223a317d2c7b22626c6f636b68617368223a7b226279746573223a5b3232342c3134382c32302c3134352c322c3234342c332c31302c3130362c302c37362c3130362c32322c38342c3138302c3233342c3139382c35312c3132392c3131372c3234362c3231342c36312c32312c3135332c3132302c3132382c35312c3134302c38392c3132312c34345d7d2c22746f6b656e6964223a22746f6b656e32222c2261646472657373223a226164647265737332222c227075624b6579486578223a227075624b657948657832222c22706f73496e646578223a312c22746f6b656e486f6c646572223a327d5d7d";
		TokenInfo info3 = new TokenInfo().parse(Utils.HEX.decode(hexString));
		byte[] bytes3 = info3.toByteArray();
		TokenInfo info4 = new TokenInfo().parse(bytes3);
		assertArrayEquals(bytes3, info4.toByteArray());
		
		// Verify that the hex string contains the expected data structure
		assertEquals(2, info3.getMultiSignAddresses().size());
		assertEquals("2", info3.getToken().getTokenid());
		assertEquals("3", info3.getToken().getTokenname());
		assertEquals("4", info3.getToken().getDescription());
		assertEquals(BigInteger.valueOf(4), info3.getToken().getAmount());
		assertEquals(3, info3.getToken().getSignnumber());
		assertEquals(2, info3.getToken().getTokenindex());
		assertEquals(true, info3.getToken().isConfirmed());
		assertEquals(true, info3.getToken().isTokenstop());
	}
}
