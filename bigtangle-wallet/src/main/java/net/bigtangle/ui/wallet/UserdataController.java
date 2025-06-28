/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.ui.wallet;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.MapValueFactory;
import javafx.stage.FileChooser;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.response.UserDataResponse;
import net.bigtangle.core.Contact;
import net.bigtangle.core.ContactInfo;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MultiSignBy;
import net.bigtangle.core.MyHomeAddress;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Uploadfile;
import net.bigtangle.core.UploadfileInfo;
import net.bigtangle.core.UserSettingData;
import net.bigtangle.core.Utils;
import net.bigtangle.core.UserSettingDataInfo;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.ui.wallet.utils.FileUtil;
import net.bigtangle.ui.wallet.utils.GuiUtils;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

public class UserdataController {

    @FXML
    public TableView<Map<String, Object>> wachtedTokenTableview;
    @FXML
    public TableColumn<Map<String, Object>, String> tokennameColumn;
    @FXML
    public TableColumn<Map<String, Object>, String> tokenidColumn;

    @FXML
    public TableView<Map<String, Object>> linkmanTableview;
    @FXML
    public TableColumn<Map<String, Object>, String> linkmanColumn;
    @FXML
    public TableColumn<Map<String, Object>, String> linkaddressColumn;

    @FXML
    public TableView<Map<String, Object>> fileTable;
    @FXML
    public TableColumn<Map<String, Object>, String> filenameColumn;
    @FXML
    public TableColumn<Map<String, Object>, String> filesizeColumn;

    @FXML
    public TableView<Map<String, Object>> otherTableview;
    @FXML
    public TableColumn<Map<String, Object>, String> keyColumn;
    @FXML
    public TableColumn<Map<String, Object>, String> valueColumn;

    @FXML
    public TableColumn<Map<String, Object>, String> domainColumn;
    @FXML
    public TextField dataclassTF;
    @FXML
    public TextField nameTF;
    @FXML
    public TextField addressTF;

    @FXML
    public TextField filepathTF;
    @FXML
    public TextField filenameTF;

    @FXML
    public TextField countryTF;
    @FXML
    public TextField provinceTF;
    @FXML
    public TextField cityTF;
    @FXML
    public TextField streetTF;
    @FXML
    public TextField emailTF;
    @FXML
    public TextArea remarkTA;
    @FXML
    public TabPane tabPane;

    @FXML
    public TextField keyTF;
    @FXML
    public TextField valueTF;
    @FXML
    public ComboBox<String> domianComboBox;

    @FXML
    public CheckBox blockSolveTypeCheckBox;
    @FXML
    public CheckBox languageCheckBox;

    public Main.OverlayUI<?> overlayUI;

    @FXML
    public void initialize() {
        try {

            List<String> list = new ArrayList<String>();
            for (ECKey ecKey : Main.walletAppKit.wallet().walletKeys(Main.getAesKey())) {
                list.add(ecKey.getPublicKeyAsHex());
            }
            tabPane.getSelectionModel().selectedIndexProperty().addListener((ov, t, t1) -> {
                int index = t1.intValue();
                switch (index) {
                case 0: {
                    initContactTableView();
                }

                    break;
                case 1: {
                    initTokenTableView();
                }

                    break;
                case 2: {
                    initMyAddress();
                }

                    break;
                case 3: {
                    initFileTableView();
                }
                case 4: {
                    initOtherTable();
                }
                    break;
                }
            });
            initContactTableView();
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    public void saveUserdata(ActionEvent event) {
        String CONTEXT_ROOT = Main.getContextRoot();
        try {
            addContact(CONTEXT_ROOT);
            initContactTableView();
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    public void saveOther(ActionEvent event) {
        String CONTEXT_ROOT = Main.getContextRoot();
        try {

            List<String> list = new ArrayList<String>();
            for (ECKey ecKey : Main.walletAppKit.wallet().walletKeys(Main.getAesKey())) {
                list.add(ecKey.getPublicKeyAsHex());
            }
            String type = domianComboBox.getValue();

            Main.addToken(CONTEXT_ROOT, valueTF.getText(), type, type);
            initOtherTableView4file();
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    public void addContact(String contextRoot) throws Exception {
        HashMap<String, String> requestParam = new HashMap<String, String>();
        byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
                Json.jsonmapper().writeValueAsString(requestParam));
        Block block = Main.params.getDefaultSerializer().makeBlock(data);
        block.setBlockType(BlockType.BLOCKTYPE_USERDATA);

        List<ECKey> issuedKeys = Main.walletAppKit.wallet().walletKeys(Main.getAesKey());

        ECKey pubKeyTo = null;

        pubKeyTo = issuedKeys.get(0);

        Transaction coinbase = new Transaction(Main.params);
        Contact contact = new Contact();
        contact.setName(nameTF.getText());
        contact.setAddress(addressTF.getText());
        ContactInfo contactInfo = (ContactInfo) Main.getUserdata(DataClassName.CONTACTINFO.name(), false);

        List<Contact> list = contactInfo.getContactList();
        list.add(contact);
        contactInfo.setContactList(list);

        coinbase.setDataClassName(DataClassName.CONTACTINFO.name());
        coinbase.setData(contactInfo.toByteArray());

        Sha256Hash sighash = coinbase.getHash();

        ECKey.ECDSASignature party1Signature = pubKeyTo.sign(sighash, Main.getAesKey());
        byte[] buf1 = party1Signature.encodeToDER();

        List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
        MultiSignBy multiSignBy0 = new MultiSignBy();
        multiSignBy0.setAddress(pubKeyTo.toAddress(Main.params).toBase58());
        multiSignBy0.setPublickey(Utils.HEX.encode(pubKeyTo.getPubKey()));
        multiSignBy0.setSignature(Utils.HEX.encode(buf1));
        multiSignBies.add(multiSignBy0);
        coinbase.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignBies));

        block.addTransaction(coinbase);
        block.solve();

        OkHttp3Util.post(contextRoot + ReqCmd.saveBlock.name(), block.bitcoinSerialize());
    }

    public void closeUI(ActionEvent event) {
        overlayUI.done();
    }

    public void removeToken(ActionEvent event) {
        try {
            Map<String, Object> rowdata = wachtedTokenTableview.getSelectionModel().getSelectedItem();
            if (rowdata == null) {
                return;
            }
            String name = (String) rowdata.get("tokenname");
            String tokenid = (String) rowdata.get("tokenid");
            String CONTEXT_ROOT = Main.getContextRoot();
            HashMap<String, String> requestParam = new HashMap<String, String>();

            byte[] data = OkHttp3Util.postAndGetBlock(CONTEXT_ROOT + ReqCmd.getTip.name(),
                    Json.jsonmapper().writeValueAsString(requestParam));

            Block block = Main.params.getDefaultSerializer().makeBlock(data);
            block.setBlockType(BlockType.BLOCKTYPE_USERDATA);

            Transaction coinbase = new Transaction(Main.params);

            UserSettingDataInfo tokenInfo = (UserSettingDataInfo) Main.getUserdata(DataClassName.TOKEN.name(), true);
            List<UserSettingData> list = tokenInfo.getUserSettingDatas();
            List<Token> tempList = new ArrayList<Token>();
            for (UserSettingData tokens : list) {
                if (name.trim().equals(tokens.getValue().trim()) && tokenid.trim().equals(tokens.getKey().trim())) {
                    continue;
                }
                tempList.add(new Token(tokens.getKey(), tokens.getValue()));
            }
       //     tokenInfo.setTokenList(tempList);

            coinbase.setDataClassName(DataClassName.TOKEN.name());
            byte[] buf1 = tokenInfo.toByteArray();
            coinbase.setData(buf1);

            block.addTransaction(coinbase);
            block.solve();
            byte[] buf = block.bitcoinSerialize();
            OkHttp3Util.post(CONTEXT_ROOT + ReqCmd.saveBlock.name(), buf);

            if (buf != null) {
                File file = new File(Main.keyFileDirectory + "/usersetting.block");
                if (file.exists()) {
                    file.delete();
                }
                FileUtil.writeFile(file, buf);
            }

            initTokenTableView();
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    public void initMyAddress() {
        try {
            MyHomeAddress myHomeAddress = (MyHomeAddress) Main.getUserdata(DataClassName.MYHOMEADDRESS.name(), false);
            countryTF.setText(myHomeAddress.getCountry());
            provinceTF.setText(myHomeAddress.getProvince());
            cityTF.setText(myHomeAddress.getCity());
            streetTF.setText(myHomeAddress.getStreet());
            emailTF.setText(myHomeAddress.getEmail());
            remarkTA.setText(myHomeAddress.getRemark());
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    public void saveMyAddress(ActionEvent event) {
        try {
            String CONTEXT_ROOT = Main.getContextRoot();
            HashMap<String, String> requestParam = new HashMap<String, String>();
            byte[] data = OkHttp3Util.postAndGetBlock(CONTEXT_ROOT + ReqCmd.getTip.name(),
                    Json.jsonmapper().writeValueAsString(requestParam));
            Block block = Main.params.getDefaultSerializer().makeBlock(data);
            block.setBlockType(BlockType.BLOCKTYPE_USERDATA);

            List<ECKey> issuedKeys = Main.walletAppKit.wallet().walletKeys(Main.getAesKey());

            ECKey pubKeyTo = null;

            pubKeyTo = issuedKeys.get(0);

            Transaction coinbase = new Transaction(Main.params);
            coinbase.setDataClassName(DataClassName.MYHOMEADDRESS.name());
            MyHomeAddress myhomeaddress = new MyHomeAddress();
            myhomeaddress.setCountry(countryTF.getText());
            myhomeaddress.setProvince(provinceTF.getText());
            myhomeaddress.setCity(cityTF.getText());
            myhomeaddress.setStreet(streetTF.getText());
            myhomeaddress.setEmail(emailTF.getText());
            myhomeaddress.setRemark(remarkTA.getText());
            coinbase.setData(myhomeaddress.toByteArray());

            Sha256Hash sighash = coinbase.getHash();
            ECKey.ECDSASignature party1Signature = pubKeyTo.sign(sighash, Main.getAesKey());
            byte[] buf1 = party1Signature.encodeToDER();

            List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
            MultiSignBy multiSignBy0 = new MultiSignBy();
            multiSignBy0.setAddress(pubKeyTo.toAddress(Main.params).toBase58());
            multiSignBy0.setPublickey(Utils.HEX.encode(pubKeyTo.getPubKey()));
            multiSignBy0.setSignature(Utils.HEX.encode(buf1));
            multiSignBies.add(multiSignBy0);
            coinbase.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignBies));

            block.addTransaction(coinbase);
            block.solve();

            OkHttp3Util.post(CONTEXT_ROOT + ReqCmd.saveBlock.name(), block.bitcoinSerialize());
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }

    }

    public void removeLinkman(ActionEvent event) {
        try {
            Map<String, Object> rowdata = linkmanTableview.getSelectionModel().getSelectedItem();
            if (rowdata == null) {
                return;
            }
            String name = (String) rowdata.get("name");
            String address = (String) rowdata.get("address");
            String CONTEXT_ROOT = Main.getContextRoot();
            HashMap<String, String> requestParam = new HashMap<String, String>();

            byte[] data = OkHttp3Util.postAndGetBlock(CONTEXT_ROOT + ReqCmd.getTip.name(),
                    Json.jsonmapper().writeValueAsString(requestParam));

            Block block = Main.params.getDefaultSerializer().makeBlock(data);
            block.setBlockType(BlockType.BLOCKTYPE_USERDATA);

            Transaction coinbase = new Transaction(Main.params);

            ContactInfo contactInfo = (ContactInfo) Main.getUserdata(DataClassName.CONTACTINFO.name(), false);
            List<Contact> list = contactInfo.getContactList();
            List<Contact> tempList = new ArrayList<Contact>();
            for (Contact contact : list) {
                if (name.trim().equals(contact.getName().trim())
                        && address.trim().equals(contact.getAddress().trim())) {
                    continue;
                }
                tempList.add(contact);
            }
            contactInfo.setContactList(tempList);

            coinbase.setDataClassName(DataClassName.CONTACTINFO.name());
            coinbase.setData(contactInfo.toByteArray());

            List<ECKey> issuedKeys = Main.walletAppKit.wallet().walletKeys(Main.getAesKey());

            ECKey pubKeyTo = null;
            pubKeyTo = issuedKeys.get(0);

            Sha256Hash sighash = coinbase.getHash();
            ECKey.ECDSASignature party1Signature = pubKeyTo.sign(sighash, Main.getAesKey());
            byte[] buf1 = party1Signature.encodeToDER();

            List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
            MultiSignBy multiSignBy0 = new MultiSignBy();
            multiSignBy0.setAddress(pubKeyTo.toAddress(Main.params).toBase58());
            multiSignBy0.setPublickey(Utils.HEX.encode(pubKeyTo.getPubKey()));
            multiSignBy0.setSignature(Utils.HEX.encode(buf1));
            multiSignBies.add(multiSignBy0);
            coinbase.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignBies));

            block.addTransaction(coinbase);
            block.solve();

            OkHttp3Util.post(CONTEXT_ROOT + ReqCmd.saveBlock.name(), block.bitcoinSerialize());
            initContactTableView();
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void initContactTableView() {
        try {
            ContactInfo contactInfo = (ContactInfo) Main.getUserdata(DataClassName.CONTACTINFO.name(), false);
            List<Contact> list = contactInfo.getContactList();
            ObservableList<Map<String, Object>> allData = FXCollections.observableArrayList();
            if (list != null && !list.isEmpty()) {
                for (Contact contact : list) {
                    Map<String, Object> map = new HashMap<String, Object>();
                    map.put("name", contact.getName());
                    map.put("address", contact.getAddress());
                    allData.add(map);
                }
                linkmanTableview.setItems(allData);
                linkmanColumn.setCellValueFactory(new MapValueFactory("name"));
                linkaddressColumn.setCellValueFactory(new MapValueFactory("address"));
            }
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void initFileTableView() {
        try {
            UploadfileInfo uploadfileInfo = (UploadfileInfo) Main.getUserdata(DataClassName.UPLOADFILE.name(), false);
            List<Uploadfile> list = uploadfileInfo.getfUploadfiles();
            ObservableList<Map<String, Object>> allData = FXCollections.observableArrayList();
            if (list != null && !list.isEmpty()) {
                for (Uploadfile contact : list) {
                    Map<String, Object> map = new HashMap<String, Object>();
                    map.put("name", contact.getName());
                    map.put("size", contact.getMaxsize());
                    map.put("fileinfo", contact.getFileinfo());
                    allData.add(map);
                }
                fileTable.setItems(allData);
                filenameColumn.setCellValueFactory(new MapValueFactory("name"));
                filesizeColumn.setCellValueFactory(new MapValueFactory("size"));
            }
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    private void initOtherTable() {
        try {
            initOtherTableView4file();
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    private void initOtherTableView4file() throws Exception {
        blockSolveTypeCheckBox.selectedProperty().addListener((ov, oldv, newv) -> {
            try {
                Main.addToken(Main.getContextRoot(), newv.toString(), DataClassName.BlockSolveType.name(),
                        DataClassName.BlockSolveType.name());
            } catch (Exception e) {
                GuiUtils.crashAlert(e);
            }
        });
        languageCheckBox.selectedProperty().addListener((ov, oldv, newv) -> {
            try {
                Main.addToken(Main.getContextRoot(), newv ? "cn" : "en", DataClassName.LANG.name(),
                        DataClassName.LANG.name());
            } catch (Exception e) {
                GuiUtils.crashAlert(e);
            }
        });
        ObservableList<String> userdata = FXCollections.observableArrayList(DataClassName.SERVERURL.name());
        domianComboBox.setItems(userdata);

        UserSettingDataInfo userSettingDataInfo = (UserSettingDataInfo) Main.getUserdata(DataClassName.UserSettingDataInfo.name(), true);
        List<UserSettingData> list = userSettingDataInfo.getUserSettingDatas();
        if (list != null && !list.isEmpty()) {
            ObservableList<Map<String, Object>> allData = FXCollections.observableArrayList();

            for (UserSettingData userSettingData : list) {

                if (DataClassName.SERVERURL.name().equals(userSettingData.getDomain())) {
                    Map<String, Object> map = new HashMap<String, Object>();
                    map.put("key", userSettingData.getKey());
                    map.put("value", userSettingData.getValue());
                    map.put("domain", userSettingData.getDomain());
                    allData.add(map);
                }
                if (DataClassName.LANG.name().equals(userSettingData.getDomain())) {
                    if ("cn".equals(userSettingData.getValue())) {
                        languageCheckBox.setSelected(true);
                    } else {
                        languageCheckBox.setSelected(false);
                    }
                }
                if (DataClassName.BlockSolveType.name().equals(userSettingData.getDomain())) {
                    if ("true".equals(userSettingData.getValue())) {
                        blockSolveTypeCheckBox.setSelected(true);
                    } else {
                        blockSolveTypeCheckBox.setSelected(false);
                    }
                }
            }
            otherTableview.setItems(allData);
            keyColumn.setCellValueFactory(new MapValueFactory("key"));
            valueColumn.setCellValueFactory(new MapValueFactory("value"));
            domainColumn.setCellValueFactory(new MapValueFactory("domain"));
        }

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void initOtherTableView(List<String> pubkeyList) {
        blockSolveTypeCheckBox.selectedProperty().addListener((ov, oldv, newv) -> {
            try {
                Main.addToken(Main.getContextRoot(), newv.toString(), DataClassName.BlockSolveType.name(),
                        DataClassName.BlockSolveType.name());
            } catch (Exception e) {
                GuiUtils.crashAlert(e);
            }
        });
        languageCheckBox.selectedProperty().addListener((ov, oldv, newv) -> {
            try {
                Main.addToken(Main.getContextRoot(), newv ? "cn" : "en", DataClassName.LANG.name(),
                        DataClassName.LANG.name());
            } catch (Exception e) {
                GuiUtils.crashAlert(e);
            }
        });
        ObservableList<String> userdata = FXCollections.observableArrayList(DataClassName.SERVERURL.name());
        domianComboBox.setItems(userdata);
        try {

            List<String> pubKeyList = new ArrayList<String>();
            for (ECKey ecKey : Main.walletAppKit.wallet().walletKeys(Main.getAesKey())) {
                pubKeyList.add(ecKey.getPublicKeyAsHex());
            }
            Type blocktype = BlockType.BLOCKTYPE_USERDATA;
            HashMap<String, Object> requestParam = new HashMap<String, Object>();
            requestParam.put("blocktype", blocktype);
            requestParam.put("pubKeyList", pubKeyList);

            String CONTEXT_ROOT = Main.getContextRoot();
           byte[] resp = OkHttp3Util.postString(CONTEXT_ROOT + ReqCmd.userDataList.name(),
                    Json.jsonmapper().writeValueAsString(requestParam));

            UserDataResponse userDataResponse = Json.jsonmapper().readValue(resp, UserDataResponse.class);
            List<String> dataList = userDataResponse.getDataList();

            ObservableList<Map<String, Object>> allData = FXCollections.observableArrayList();
            for (String hexStr : dataList) {
                byte[] data = Utils.HEX.decode(hexStr);
                HashMap<String, Object> userdataKV = Json.jsonmapper().readValue(new String(data), HashMap.class);
                List<Map<String, Object>> userSettingDatas = (List<Map<String, Object>>) userdataKV
                        .get("userSettingDatas");
                if (userSettingDatas != null && !userSettingDatas.isEmpty()) {
                    for (Map<String, Object> map : userSettingDatas) {
                        if (DataClassName.SERVERURL.name().equals(map.get("domain"))) {
                            allData.add(map);
                        }

                    }

                }

            }
            otherTableview.setItems(allData);
            keyColumn.setCellValueFactory(new MapValueFactory("key"));
            valueColumn.setCellValueFactory(new MapValueFactory("value"));
            domainColumn.setCellValueFactory(new MapValueFactory("domain"));

        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    public void initWatchedTokenTable4file() throws Exception {

        UserSettingDataInfo userSettingDataInfo = (UserSettingDataInfo) Main.getUserdata(DataClassName.UserSettingDataInfo.name(), true);
        List<UserSettingData> list = userSettingDataInfo.getUserSettingDatas();
        if (list != null && !list.isEmpty()) {
            ObservableList<Map<String, Object>> allData = FXCollections.observableArrayList();

            for (UserSettingData userSettingData : list) {

                if (DataClassName.TOKEN.name().equals(userSettingData.getDomain())) {
                    Map<String, Object> map = new HashMap<String, Object>();
                    map.put("key", userSettingData.getKey());
                    map.put("value", userSettingData.getValue());
                    map.put("domain", userSettingData.getDomain());
                    allData.add(map);
                }
            }
            wachtedTokenTableview.setItems(allData);
            tokennameColumn.setCellValueFactory(new MapValueFactory("value"));
            tokenidColumn.setCellValueFactory(new MapValueFactory("key"));
        }

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void initWatchedTokenTable() throws Exception {

        List<String> pubKeyList = new ArrayList<String>();
        for (ECKey ecKey : Main.walletAppKit.wallet().walletKeys(Main.getAesKey())) {
            pubKeyList.add(ecKey.getPublicKeyAsHex());
        }
        Type blocktype = BlockType.BLOCKTYPE_USERDATA;
        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        requestParam.put("blocktype", blocktype);
        requestParam.put("pubKeyList", pubKeyList);

        String CONTEXT_ROOT = Main.getContextRoot();
       byte[] resp = OkHttp3Util.postString(CONTEXT_ROOT + ReqCmd.userDataList.name(),
                Json.jsonmapper().writeValueAsString(requestParam));

        UserDataResponse userDataResponse = Json.jsonmapper().readValue(resp, UserDataResponse.class);
        List<String> dataList = userDataResponse.getDataList();

        ObservableList<Map<String, Object>> allData = FXCollections.observableArrayList();
        for (String hexStr : dataList) {
            byte[] data = Utils.HEX.decode(hexStr);
            HashMap<String, Object> userdataKV = Json.jsonmapper().readValue(new String(data), HashMap.class);
            List<Map<String, Object>> userSettingDatas = (List<Map<String, Object>>) userdataKV.get("userSettingDatas");
            if (userSettingDatas != null && !userSettingDatas.isEmpty()) {
                for (Map<String, Object> map : userSettingDatas) {
                    if (DataClassName.TOKEN.name().equals(map.get("domain"))
                            || DataClassName.TOKEN.name().equals(map.get("domain"))) {
                        allData.add(map);
                    }

                }
            }
            wachtedTokenTableview.setItems(allData);
            tokennameColumn.setCellValueFactory(new MapValueFactory("value"));
            tokenidColumn.setCellValueFactory(new MapValueFactory("key"));

        }
    }

    private void initTokenTableView() {
        try {
            initWatchedTokenTable4file();
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    public void uploadFile(ActionEvent event) {
        try {
            String CONTEXT_ROOT = Main.getContextRoot();
            final FileChooser fileChooser = new FileChooser();
            File file = fileChooser.showOpenDialog(null);
            if (filenameTF.getText() == null || filenameTF.getText().isEmpty()) {
                filenameTF.setText(file.getName());
            }

            filepathTF.setText(file.getAbsolutePath());
            if (file.length() > NetworkParameters.MAX_DEFAULT_BLOCK_SIZE - 20 * 1000) {
                GuiUtils.informationalAlert("", Main.getText("fileTooLarge"), "");
                return;
            }
            byte[] buf = FileUtil.readFile(file);
            if (buf == null) {
                return;
            }
            HashMap<String, String> requestParam = new HashMap<String, String>();
            byte[] data = OkHttp3Util.postAndGetBlock(CONTEXT_ROOT + ReqCmd.getTip.name(),
                    Json.jsonmapper().writeValueAsString(requestParam));
            Block block = Main.params.getDefaultSerializer().makeBlock(data);
            block.setBlockType(BlockType.BLOCKTYPE_USERDATA);

            List<ECKey> issuedKeys = Main.walletAppKit.wallet().walletKeys(Main.getAesKey());

            ECKey pubKeyTo = null;

            pubKeyTo = issuedKeys.get(0);

            Transaction coinbase = new Transaction(Main.params);
            Uploadfile uploadfile = new Uploadfile();
            uploadfile.setName(filenameTF.getText());
            uploadfile.setMaxsize(file.length());

            UploadfileInfo uploadfileInfo = (UploadfileInfo) Main.getUserdata(DataClassName.UPLOADFILE.name(), false);
            List<Uploadfile> uploadfiles = uploadfileInfo.getfUploadfiles();
            uploadfiles.add(uploadfile);
            uploadfileInfo.setfUploadfiles(uploadfiles);
            uploadfile.setFileinfo(buf);
            uploadfile.setFileinfoHex(Utils.HEX.encode(buf));
            coinbase.setDataClassName(DataClassName.UPLOADFILE.name());
            coinbase.setData(uploadfileInfo.toByteArray());

            Sha256Hash sighash = coinbase.getHash();
            ECKey.ECDSASignature party1Signature = pubKeyTo.sign(sighash, Main.getAesKey());
            byte[] buf1 = party1Signature.encodeToDER();

            List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
            MultiSignBy multiSignBy0 = new MultiSignBy();
            multiSignBy0.setAddress(pubKeyTo.toAddress(Main.params).toBase58());
            multiSignBy0.setPublickey(Utils.HEX.encode(pubKeyTo.getPubKey()));
            multiSignBy0.setSignature(Utils.HEX.encode(buf1));
            multiSignBies.add(multiSignBy0);
            coinbase.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignBies));

            block.addTransaction(coinbase);
            block.solve();

            OkHttp3Util.post(CONTEXT_ROOT + ReqCmd.saveBlock.name(), block.bitcoinSerialize());
            initFileTableView();
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }

    public void downloadFile(ActionEvent event) {
        try {
            Map<String, Object> rowdata = fileTable.getSelectionModel().getSelectedItem();
            if (rowdata == null || rowdata.isEmpty()) {
                return;
            }
            byte[] fileinfo = (byte[]) rowdata.get("fileinfo");
            final FileChooser fileChooser = new FileChooser();
            File file = fileChooser.showSaveDialog(null);
            if (file == null) {
                return;
            }
            // wirte file
            FileUtil.writeFile(file, fileinfo);
            overlayUI.done();
        } catch (Exception e) {
            GuiUtils.crashAlert(e);
        }
    }
}
