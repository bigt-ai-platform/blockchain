package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Contact;
import net.bigtangle.core.ContactInfo;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UserSettingData;
import net.bigtangle.core.UserSettingDataInfo;
import net.bigtangle.core.Utils;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;


public class UserdataTest extends AbstractIntegrationTest {
    @Test
    public void testSaveUserData() throws Exception {
        // Ensure tips queue is populated before wallet operations
        mcmcService.calcNewBlockPrototype(store);

        ECKey outKey = new ECKey();
        Transaction transaction = new Transaction(networkParameters);
        UserSettingData contact = new UserSettingData();
        contact.setDomain("contact");
        contact.setKey("testname");
        contact.setValue(outKey.toAddress(networkParameters).toBase58());
        UserSettingDataInfo contactInfo0 = new UserSettingDataInfo();
        List<UserSettingData> list = new ArrayList<UserSettingData>();
        list.add(contact);
        contactInfo0.setUserSettingDatas(list);
        // Token list displayname + tokenid

        transaction.setDataClassName(DataClassName.UserSettingDataInfo.name());
        transaction.setData(contactInfo0.toByteArray());


        // TODO encrypt and decrypt the  UserSettingData


       wallet.saveUserdata(outKey, transaction,true,null);
        Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
        Block dataBlock = drainMempoolAndCreateBlock(predecessor, predecessor);
        makeRewardBlock(dataBlock);


        UserSettingDataInfo contactInfo1 =wallet.getUserSettingDataInfo(outKey,true);
        assertTrue(contactInfo1.getUserSettingDatas().size() == 1);

        UserSettingData contact0 = contactInfo1.getUserSettingDatas().get(0);
        assertTrue("testname".equals(contact0.getKey()));

        transaction = new Transaction(networkParameters);
        contactInfo1.setUserSettingDatas(new ArrayList<UserSettingData>());
        transaction.setDataClassName(DataClassName.UserSettingDataInfo.name());
        transaction.setData(contactInfo1.toByteArray());

       // Ensure tips queue is updated before second wallet operation
       mcmcService.calcNewBlockPrototype(store);
       wallet.saveUserdata(outKey, transaction,true,null);
        Block predecessor2 = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
        Block clearBlock = drainMempoolAndCreateBlock(predecessor2, predecessor2);
        makeRewardBlock(clearBlock);
 

        contactInfo1 =wallet.getUserSettingDataInfo(outKey,true);
        assertTrue(contactInfo1.getUserSettingDatas().size() == 0);
    }
    @Test
    public void testSaveUserDataWithECKey() throws Exception {

        // Ensure tips queue is populated before wallet operations
        mcmcService.calcNewBlockPrototype(store);

        ECKey outKey = new ECKey();
        Transaction transaction = new Transaction(networkParameters);
        UserSettingData contact = new UserSettingData();
        contact.setDomain("contact");
        contact.setKey("testname");
        contact.setValue(outKey.toAddress(networkParameters).toBase58());
        UserSettingDataInfo contactInfo0 = new UserSettingDataInfo();
        List<UserSettingData> list = new ArrayList<UserSettingData>();
        list.add(contact);
        contactInfo0.setUserSettingDatas(list);
        // Token list displayname + tokenid

        transaction.setDataClassName(DataClassName.UserSettingDataInfo.name());
        transaction.setData(contactInfo0.toByteArray());      
        
       wallet.saveUserdata(outKey, transaction,true,null);
        Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
        Block dataBlock = drainMempoolAndCreateBlock(predecessor, predecessor);
        makeRewardBlock(dataBlock);


        UserSettingDataInfo contactInfo1 = wallet.getUserSettingDataInfo(outKey,true);
        assertTrue(contactInfo1.getUserSettingDatas().size() == 1);

        UserSettingData contact0 = contactInfo1.getUserSettingDatas().get(0);
        assertTrue("testname".equals(contact0.getKey()));


    }
    @Test
    public void testServerURL() throws Exception {
        // Ensure tips queue is populated
        try {
            mcmcService.update(store);
        } catch (Exception e) {
            // If update fails, continue anyway
        }

        HashMap<String, String> requestParam = new HashMap<String, String>();
        byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
                Json.jsonmapper().writeValueAsString(requestParam));
        Block block = networkParameters.getDefaultSerializer().makeBlock(data);
        block.setBlockType(BlockType.BLOCKTYPE_USERDATA);
        ECKey outKey = new ECKey();

        Transaction transaction = new Transaction(networkParameters);
        Contact contact = new Contact();
        contact.setName("bigtangle.org");
        contact.setAddress(outKey.toAddress(networkParameters).toBase58());
        ContactInfo contactInfo0 = new ContactInfo();
        List<Contact> list = new ArrayList<Contact>();
        list.add(contact);
        contactInfo0.setContactList(list);

        transaction.setDataClassName(DataClassName.SERVERURL.name());
        transaction.setData(contactInfo0.toByteArray());
        // TODO encrypt and decrypt the contactInfo0
       wallet.saveUserdata(outKey, transaction,false,null);
        Block predecessor2 = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
        Block dataBlock2 = drainMempoolAndCreateBlock(predecessor2, predecessor2);

    }

  //  @Test
    public void testExchangeUserdata() throws Exception {

        HashMap<String, String> requestParam = new HashMap<String, String>();
        byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
                Json.jsonmapper().writeValueAsString(requestParam));
        Block block = networkParameters.getDefaultSerializer().makeBlock(data);
        block.setBlockType(BlockType.BLOCKTYPE_USERDATA);
        ECKey outKey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv), Utils.HEX.decode(testPub));
        makeTestToken(outKey, BigInteger.valueOf(1000 * 1000), new ArrayList<>(), 0);

        Transaction transaction = new Transaction(networkParameters);
        Contact contact = new Contact();
        contact.setName("mytokenforexcahnge");
        contact.setAddress(outKey.getPublicKeyAsHex());
        ContactInfo contactInfo0 = new ContactInfo();
        List<Contact> list = new ArrayList<Contact>();
        list.add(contact);
        contactInfo0.setContactList(list);

        transaction.setDataClassName(DataClassName.CONTACTINFO.name());
        transaction.setData(contactInfo0.toByteArray());

       Block b=wrapTransaction(wallet.saveUserdata(outKey, transaction,false,null));
        makeRewardBlock(b);
        UserSettingDataInfo contactInfo1 =wallet.getUserSettingDataInfo(outKey,false);
        assertTrue(contactInfo1.getUserSettingDatas().size() == 1);

     
    }

}
