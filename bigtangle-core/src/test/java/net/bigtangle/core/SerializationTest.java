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
import net.bigtangle.encrypt.ECIESCoder;

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

}
