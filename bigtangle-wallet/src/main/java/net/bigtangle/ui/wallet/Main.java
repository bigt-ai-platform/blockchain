/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.bigtangle.ui.wallet;

import static net.bigtangle.ui.wallet.utils.GuiUtils.blurIn;
import static net.bigtangle.ui.wallet.utils.GuiUtils.blurOut;
import static net.bigtangle.ui.wallet.utils.GuiUtils.checkGuiThread;
import static net.bigtangle.ui.wallet.utils.GuiUtils.explodeOut;
import static net.bigtangle.ui.wallet.utils.GuiUtils.fadeIn;
import static net.bigtangle.ui.wallet.utils.GuiUtils.fadeOutAndRemove;
import static net.bigtangle.ui.wallet.utils.GuiUtils.zoomIn;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.Contact;
import net.bigtangle.core.ContactInfo;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MultiSignBy;
import net.bigtangle.core.MyHomeAddress;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UploadfileInfo;
import net.bigtangle.core.UserSettingData;
import net.bigtangle.core.Utils;
import net.bigtangle.core.UserSettingDataInfo;
import net.bigtangle.core.exception.ProtocolException;
import net.bigtangle.core.response.GetOutputsResponse;
import net.bigtangle.core.response.GetTokensResponse;
import net.bigtangle.crypto.KeyCrypterScrypt;
import net.bigtangle.kits.WalletAppKit;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.params.TestParams;
import net.bigtangle.ui.wallet.controls.NotificationBarPane;
import net.bigtangle.ui.wallet.utils.FileUtil;
import net.bigtangle.ui.wallet.utils.GuiUtils;
import net.bigtangle.ui.wallet.utils.TextFieldValidator;
import net.bigtangle.utils.BriefLogFormatter;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.utils.Threading;
import net.bigtangle.wallet.DeterministicSeed;

public class Main extends Application {
    public static NetworkParameters params = MainNetParams.get();
    public static final String APP_NAME = "Bigtangle  Wallet  " + Main.version + "-11-22";
    public static final String version = "0.3.5.0";
    public static String keyFileDirectory = ".";
    public static String keyFilePrefix = "bigtangle";
    public static WalletAppKit walletAppKit;
    public static Main instance;

    private StackPane uiStack;
    private Pane mainUI;
    public MainController controller;
    public NotificationBarPane notificationBar;
    public Stage mainWindow;
    private ObservableList<CoinModel> coinData = FXCollections.observableArrayList();
    private ObservableList<UTXOModel> utxoData = FXCollections.observableArrayList();

    public static String IpAddress = "";
    public static String versionserver = "https://bigtangle.org/";
    public static FXMLLoader loader;

    public static String lang = "zh";
    public static String password = "";
    public static int numberOfEmptyBlocks = 3;
    public static boolean emptyBlocks = true;
    public static Map<String, Set<String>> validTokenMap = new HashMap<String, Set<String>>();
    public static Map<String, Set<String>> validOutputMultiMap = new HashMap<String, Set<String>>();
    public static Set<String> validAddressSet = new HashSet<String>();
    public static Set<String> validTokenSet = new HashSet<String>();
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static List<String> userdataList = new ArrayList<String>();
    // TODO as instance variable, not static
    private static UserSettingDataInfo watchedtokenInfo;
    private static KeyParameter aesKey;

    @Override
    public void start(Stage mainWindow) throws Exception {
        try {
            // System.setProperty("https.proxyHost",
            // "anwproxy.anwendungen.localnet.de");
            // System.setProperty("https.proxyPort", "3128");
            realStart(mainWindow, Main.lang);
        } catch (Throwable e) {
            GuiUtils.crashAlert(e);
            // throw e;
        }
    }

    public static String getText(String s) {
        ResourceBundle rb = ResourceBundle.getBundle("net.bigtangle.ui.wallet.message", Locale.getDefault());
        if (!"zh".equalsIgnoreCase(lang)) {
            rb = ResourceBundle.getBundle("net.bigtangle.ui.wallet.message_en", Locale.ENGLISH);
        } else {
            rb = ResourceBundle.getBundle("net.bigtangle.ui.wallet.message", Locale.CHINESE);
        }
        return rb.getString(s);

    }

    public static void addToken(String contextRoot, String tokenname, String tokenid, String type) throws Exception { }

    /**
     * 
     * @param version1
     * @param version2
     * @return
     */
    public static int compareVersion(String version1, String version2) throws Exception {
        if (version1 == null || version2 == null) {
            throw new Exception("compareVersion error:illegal params.");
        }
        String[] versionArray1 = version1.split("\\.");
        String[] versionArray2 = version2.split("\\.");
        int idx = 0;
        int minLength = Math.min(versionArray1.length, versionArray2.length);
        int diff = 0;
        while (idx < minLength && (diff = versionArray1[idx].length() - versionArray2[idx].length()) == 0
                && (diff = versionArray1[idx].compareTo(versionArray2[idx])) == 0) {
            ++idx;
        }
        diff = (diff != 0) ? diff : versionArray1.length - versionArray2.length;
        return diff;
    }

    public static String getString(Object object) {

        return object == null ? " " : String.valueOf(object).trim();

    }

    public static void initAeskey(KeyParameter aesKey) {
        final KeyCrypterScrypt keyCrypter = (KeyCrypterScrypt) Main.walletAppKit.wallet().getKeyCrypter();
        if (!"".equals(Main.password.trim())) {
            aesKey = keyCrypter.deriveKey(Main.password);
        }
    }

    public void realStart(Stage mainWindow, String language) throws IOException {

        this.mainWindow = mainWindow;
        // mainWindow.setMaximized(true);
        instance = this;
        // Show the crash dialog for any exceptions that we don't handle and
        // that hit the main loop.
        GuiUtils.handleCrashesOnThisThread();

        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            // We could match the Mac Aqua style here, except that (a) Modena
            // doesn't look that bad, and (b)
            // the date picker widget is kinda broken in AquaFx and I can't be
            // bothered fixing it.
            // AquaFx.style();
        }

        // Load the GUI. The MainController class will be automagically created
        // and wired up.
        URL location = getClass().getResource("main.fxml");
        loader = new FXMLLoader(location);
        String resourceFile = "net.bigtangle.ui.wallet.message";
        lang = language;
        Locale locale = Locale.CHINESE;
        if (!"zh".equals(lang)) {
            resourceFile += "_en";
            locale = Locale.ENGLISH;
        }
        ResourceBundle resourceBundle = ResourceBundle.getBundle(resourceFile, locale);
        loader.setResources(resourceBundle);
        walletAppKit = new WalletAppKit(params, new File(Main.keyFileDirectory), Main.keyFilePrefix);

        // set local kafka to send
        if (!Locale.CHINESE.equals(locale)) {

            if ("".equals(IpAddress))
                IpAddress = "https://p.bigtangle.de:8088";
        } else {

            if ("".equals(IpAddress))
                IpAddress = "https://p.bigtangle.org:8088";

        }

        mainUI = loader.load();
        controller = loader.getController();
        // Configure the window with a StackPane so we can overlay things on top
        // of the main UI, and a
        // NotificationBarPane so we can slide messages and progress bars in
        // from the bottom. Note that
        // ordering of the construction and connection matters here, otherwise
        // we get (harmless) CSS error
        // spew to the logs.
        notificationBar = new NotificationBarPane(mainUI);
        mainWindow.setTitle(APP_NAME);
        uiStack = new StackPane();
        Scene scene = new Scene(uiStack);
        TextFieldValidator.configureScene(scene); // Add CSS that we need.
        scene.getStylesheets().add(getClass().getResource("wallet.css").toString());
        uiStack.getChildren().add(notificationBar);
        mainWindow.setScene(scene);

        // Make log output concise.
        BriefLogFormatter.init();
        // Tell bitcoinj to run event handlers on the JavaFX UI thread. This
        // keeps things simple and means
        // we cannot forget to switch threads when adding event handlers.
        // Unfortunately, the DownloadListener
        // we give to the app kit is currently an exception and runs on a
        // library thread. It'll get fixed in
        // a future version.
        Threading.USER_THREAD = Platform::runLater;
        // Create the app kit. It won't do any heavyweight initialization until
        // after we start it.
        setupWalletKit(null);
        mainWindow.getIcons().add(new Image(getClass().getResourceAsStream("bigtangle_logo_plain.png")));

        mainWindow.show();

        WalletSetPasswordController.estimateKeyDerivationTimeMsec();

    }

    public static void addAddress2block(String name, String address) throws Exception {
        String CONTEXT_ROOT = getContextRoot();
        HashMap<String, String> requestParam = new HashMap<String, String>();
        byte[] data = OkHttp3Util.postAndGetBlock(CONTEXT_ROOT + ReqCmd.getTip.name(),
                Json.jsonmapper().writeValueAsString(requestParam));
        Block block = Main.params.getDefaultSerializer().makeBlock(data);
        block.setBlockType(BlockType.BLOCKTYPE_USERDATA);

        List<ECKey> issuedKeys = Main.walletAppKit.wallet().walletKeys(getAesKey());

        ECKey pubKeyTo = null;

        pubKeyTo = issuedKeys.get(0);

        Transaction coinbase = new Transaction(Main.params);
        Contact contact = new Contact();
        contact.setName(name);
        contact.setAddress(address);
        ContactInfo contactInfo = (ContactInfo) getUserdata(DataClassName.CONTACTINFO.name(), false);

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

        OkHttp3Util.post(CONTEXT_ROOT + ReqCmd.saveBlock.name(), block.bitcoinSerialize());
    }

    public static KeyParameter getAesKey() {
        if (aesKey == null) {
            final KeyCrypterScrypt keyCrypter = (KeyCrypterScrypt) Main.walletAppKit.wallet().getKeyCrypter();
            if (!"".equals(Main.password.trim())) {
                aesKey = keyCrypter.deriveKey(Main.password);
            }
        }
        return aesKey;
    }

    public static String getString4block(List<String> list) throws Exception {
        StringBuffer temp = new StringBuffer("");
        if (list != null && !list.isEmpty()) {
            for (String string : list) {
                temp.append(string + "\n\r");
            }
        }
        return temp.toString();

    }

    public static List<String> initAddress4block() throws Exception {

        List<String> addressList = new ArrayList<String>();
        ContactInfo contactInfo = (ContactInfo) getUserdata(DataClassName.CONTACTINFO.name(), false);
        if (contactInfo == null)
            return addressList;
        List<Contact> list = contactInfo.getContactList();
        for (Contact contact : list) {
            addressList.add(contact.getName() + "," + contact.getAddress());
        }
        return addressList;
    }

    /*
     * In test we do not ask user to add token to watched list
     */
    public static UserSettingDataInfo getWatched() throws Exception {
        if (watchedtokenInfo != null) {
            return watchedtokenInfo;
        }
        watchedtokenInfo = new UserSettingDataInfo();
      
        return watchedtokenInfo;
    }

    public static void resetWachted() throws Exception {

        watchedtokenInfo = new UserSettingDataInfo();
     
    }

    public static boolean isTokenInWatched(String tokenid) throws Exception {
        if (NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(tokenid)) {
            return true;
        }
        if (watchedtokenInfo == null) {
            getWatched();
        }
        watchedtokenInfo = new UserSettingDataInfo();
      
        return false;
    }

    public static List<String> initToken4blockFromUsersetting() throws Exception {
        UserSettingDataInfo tokenInfo = (UserSettingDataInfo) getUserdata(DataClassName.TOKEN.name(), true);
        if (tokenInfo == null) {
            return null;
        }
        List<UserSettingData> list = tokenInfo.getUserSettingDatas();
        List<String> addressList = new ArrayList<String>();
        if (list != null && !list.isEmpty()) {
            for (UserSettingData userSettingData : list) {
                if (userSettingData.getDomain().equals(DataClassName.TOKEN.name())
                        && userSettingData.getValue().endsWith(":" + Main.getText("Token"))) {
                    addressList.add(userSettingData.getKey() + ","
                            + userSettingData.getValue().substring(0, userSettingData.getValue().lastIndexOf(":")));
                }

            }
        }
        return addressList;
    }

    public static GetTokensResponse getAllTokens() throws Exception {
        Map<String, Object> requestParam = new HashMap<String, Object>();
        requestParam.put("name", null);
       byte[] response = OkHttp3Util.post(getContextRoot() + ReqCmd.searchTokens.name(),
                Json.jsonmapper().writeValueAsString(requestParam).getBytes());

        return Json.jsonmapper().readValue(response, GetTokensResponse.class);

    }

    public static Token getTokenById(String tokenid) throws Exception {

        String ContextRoot = Main.getContextRoot();
        HashMap<String, Object> requestParam0 = new HashMap<String, Object>();
        requestParam0.put("tokenid", tokenid);
       byte[] resp = OkHttp3Util.postString(ContextRoot + ReqCmd.getTokenById.name(),
                Json.jsonmapper().writeValueAsString(requestParam0));

        return Json.jsonmapper().readValue(resp, GetTokensResponse.class).getTokens().get(0);

    }

    public static String getTokenid(String string) {
        if (string != null) {
            if (string.contains(":")) {
                string = string.substring(string.indexOf(":") + 1);
            }
            string = string.trim();
        }
        return string;

    }

    public void setupWalletKit(@Nullable DeterministicSeed seed) {

        if (seed != null)
            walletAppKit.restoreWalletFromSeed(seed);
    }

    private Node stopClickPane = new Pane();

    public class OverlayUI<T> {
        public Node ui;
        public T controller;

        public OverlayUI(Node ui, T controller) {
            this.ui = ui;
            this.controller = controller;
        }

        public void show() {
            checkGuiThread();
            if (currentOverlay == null) {
                uiStack.getChildren().add(stopClickPane);
                uiStack.getChildren().add(ui);
                blurOut(mainUI);
                // darken(mainUI);
                ui.setOpacity(1.0);
                fadeIn(ui);
                zoomIn(ui);
            } else {
                // Do a quick transition between the current overlay and the
                // next.
                // Bug here: we don't pay attention to changes in
                // outsideClickDismisses.
                explodeOut(currentOverlay.ui);
                fadeOutAndRemove(uiStack, currentOverlay.ui);
                uiStack.getChildren().add(ui);
                ui.setOpacity(1.0);
                fadeIn(ui, 100);
                zoomIn(ui, 100);
            }
            currentOverlay = this;
        }

        public void outsideClickDismisses() {
            stopClickPane.setOnMouseClicked((ev) -> done());
        }

        public void done() {
            checkGuiThread();
            if (ui == null)
                return; // In the middle of being dismissed and got an extra
                        // click.
            explodeOut(ui);
            fadeOutAndRemove(uiStack, ui, stopClickPane);
            blurIn(mainUI);
            // undark(mainUI);
            this.ui = null;
            this.controller = null;
            currentOverlay = null;
        }
    }

    @Nullable
    private OverlayUI<?> currentOverlay;

    public <T> OverlayUI<T> overlayUI(Node node, T controller) {
        checkGuiThread();
        OverlayUI<T> pair = new OverlayUI<T>(node, controller);
        // Auto-magically set the overlayUI member, if it's there.
        try {
            controller.getClass().getField("overlayUI").set(controller, pair);
        } catch (IllegalAccessException | NoSuchFieldException ignored) {
        }
        pair.show();
        return pair;
    }

    /**
     * Loads the FXML file with the given name, blurs out the main UI and puts
     * this one on top.
     */
    public <T> OverlayUI<T> overlayUI(String name) {
        try {
            checkGuiThread();
            // Load the UI from disk.
            URL location = GuiUtils.getResource(name);
            FXMLLoader loader = new FXMLLoader(location);
            String resourceFile = "net.bigtangle.ui.wallet.message";
            Locale locale = Locale.CHINESE;
            if (!"zh".equals(Main.lang)) {
                resourceFile += "_en";
                locale = Locale.ENGLISH;
            }
            ResourceBundle resourceBundle = ResourceBundle.getBundle(resourceFile, locale);
            loader.setResources(resourceBundle);
            Pane ui = loader.load();
            T controller = loader.getController();
            OverlayUI<T> pair = new OverlayUI<T>(ui, controller);
            // Auto-magically set the overlayUI member, if it's there.
            try {
                if (controller != null)
                    controller.getClass().getField("overlayUI").set(controller, pair);
            } catch (IllegalAccessException | NoSuchFieldException ignored) {
                ignored.printStackTrace();
            }
            pair.show();
            return pair;
        } catch (IOException e) {
            throw new RuntimeException(e); // Can't happen.
        }
    }

    public static void main(String[] args) {
        String systemLang = Locale.getDefault().getLanguage();
        // String systemName = System.getProperty("os.name").toLowerCase();
        File file = new File(keyFileDirectory + "/usersetting.block");
        byte[] data = null;
        if (file.exists()) {
            data = FileUtil.readFile(file);
        }
        boolean flag1 = false;
        boolean flag2 = false;
        if (data != null && data.length != 0) {

            Block block = null;
            try {
                block = Main.params.getDefaultSerializer().makeBlock(data);
            } catch (ProtocolException e) {
                // TODO: handle exception with version changed
                log.warn("", e);
            }
            if (block != null) {
                boolean flag = true;
                if (block.getTransactions() == null || block.getTransactions().isEmpty()) {
                    flag = false;
                }
                if (flag) {
                    Transaction transaction = block.getTransactions().get(block.getTransactions().size() - 1);
                    byte[] buf = transaction.getData();
                    try {
                        UserSettingDataInfo userSettingDataInfo = new UserSettingDataInfo().parse(buf);
                        List<UserSettingData> list = userSettingDataInfo.getUserSettingDatas();
                        if (list != null && !list.isEmpty()) {
                            for (UserSettingData userSettingData : list) {
                                if (userSettingData.getDomain().equals(DataClassName.SERVERURL.name())) {
                                    if (userSettingData.getValue() != null
                                            || !userSettingData.getValue().trim().isEmpty()) {
                                        flag1 = true;
                                        IpAddress = userSettingData.getValue();
                                    }

                                }
                                if (userSettingData.getDomain().equals(DataClassName.LANG.name())) {
                                    if (userSettingData.getValue() != null
                                            || !userSettingData.getValue().trim().isEmpty()) {
                                        flag2 = true;
                                        lang = userSettingData.getValue();
                                    }

                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("", e);
                    }
                }

            }
        }
        if (args == null || args.length == 0) {
            if (!flag2) {
                lang = systemLang;
            }

            keyFileDirectory = System.getProperty("user.home");
            keyFilePrefix = System.getProperty("user.name");
        }
        if (args != null && args.length >= 2) {
            if (!flag2)
                lang = args[0];
            keyFileDirectory = new File(args[1]).getParent();
            String temp = new File(args[1]).getName();
            if (temp.contains(".")) {
                keyFilePrefix = temp.substring(0, temp.lastIndexOf("."));
            } else {
                keyFilePrefix = temp;
            }
            if (args.length >= 3) {
                if (!flag1)
                    IpAddress = args[2];
            }
            if (args.length >= 4) { 
                    if ("test".equals(args[3])) {
                        params = TestParams.get();
                    }
               
            }

        }

        launch(args);
    }

    public static void addUsersettingData() throws Exception {

        addToken(getContextRoot(), lang, DataClassName.LANG.name(), DataClassName.LANG.name());
        addToken(getContextRoot(), IpAddress, DataClassName.SERVERURL.name(), DataClassName.SERVERURL.name());

    }

    public ObservableList<CoinModel> getCoinData() {
        return coinData;
    }

    public void setCoinData(ObservableList<CoinModel> coinData) {
        this.coinData = coinData;
    }

    public ObservableList<UTXOModel> getUtxoData() {
        return utxoData;
    }

    public void setUtxoData(ObservableList<UTXOModel> utxoData) {
        this.utxoData = utxoData;
    }
 
 

    public static List<UTXO> getUTXOWithECKeyList(List<ECKey> ecKeys, String tokenid) throws Exception {
        List<String> pubKeyHashs = new ArrayList<String>();

        for (ECKey ecKey : ecKeys) {
            pubKeyHashs.add(Utils.HEX.encode(ecKey.getPubKeyHash()));
        }
        return getUTXOWithPubKeyHash(pubKeyHashs, tokenid);
    }

    public static List<UTXO> getUTXOWithPubKeyHash(byte[] pubKeyHash, String tokenid) throws Exception {
        List<String> pubKeyHashs = new ArrayList<String>();
        pubKeyHashs.add(Utils.HEX.encode(pubKeyHash));
        return getUTXOWithPubKeyHash(pubKeyHashs, tokenid);
    }

    public static List<UTXO> getUTXOWithPubKeyHash(List<String> pubKeyHashs, String tokenid) throws Exception {
        List<UTXO> listUTXO = new ArrayList<UTXO>();
        String ContextRoot = Main.IpAddress + "/"; // http://" + Main.IpAddress
                                                   // + ":" + Main.port + "/";

       byte[] response = OkHttp3Util.post(ContextRoot + ReqCmd.getOutputs.name(),
                Json.jsonmapper().writeValueAsString(pubKeyHashs).getBytes());
        log.debug("tokenid:" + tokenid);
        log.debug("response:" + response);
        GetOutputsResponse getOutputsResponse = Json.jsonmapper().readValue(response, GetOutputsResponse.class);
        for (UTXO utxo : getOutputsResponse.getOutputs()) {
            if (!utxo.getTokenId().equals(tokenid)) {
                continue;
            }
            if (utxo.getValue().getValue().signum() > 0) {
                listUTXO.add(utxo);
            }
        }
        return listUTXO;
    }

    public static Coin calculateTotalUTXOList(byte[] pubKeyHash, String tokenid) throws Exception {
        List<String> pubKeyHashs = new ArrayList<String>();
        pubKeyHashs.add(Utils.HEX.encode(pubKeyHash));

        List<UTXO> listUTXO = getUTXOWithPubKeyHash(pubKeyHashs, tokenid);
        Coin amount = Coin.valueOf(0, tokenid);
        if (listUTXO == null || listUTXO.isEmpty()) {
            return amount;
        }
        for (UTXO utxo : listUTXO) {
            amount = amount.add(utxo.getValue());
        }
        return amount;
    }

    public static boolean checkResponse(String resp) throws JsonParseException, JsonMappingException, IOException {
        @SuppressWarnings("unchecked")
        HashMap<String, Object> result2 = Json.jsonmapper().readValue(resp, HashMap.class);
        int error = (Integer) result2.get("errorcode");
        return error > 0;
    }

    /**
     * is HH:mm:ss
     * 
     * @param time
     * @return
     */
    public static boolean isTime(String time) {
        Pattern p = Pattern
                .compile("((((0?[0-9])|([1][0-9])|([2][0-4]))\\:([0-5]?[0-9])((\\s)|(\\:([0-5]?[0-9])))))?$");
        return p.matcher(time).matches();
    }

    public static String getContextRoot() {
        return Main.IpAddress + "/"; // http://" + Main.IpAddress + ":" +
                                     // Main.port + "/";
    }

    public static Serializable getUserdata(String type, boolean isClient) throws Exception {
        Serializable userdata = new UserSettingDataInfo();
        if (isClient) {
            File file = new File(Main.keyFileDirectory + "/usersetting.block");
            byte[] data = null;
            if (file.exists()) {
                data = FileUtil.readFile(file);
            }
            if (data != null && data.length != 0) {
                Block block = null;

                block = Main.params.getDefaultSerializer().makeBlock(data);
                if (block != null) {
                    boolean flag = true;
                    if (block.getTransactions() == null || block.getTransactions().isEmpty()) {
                        flag = false;
                    }
                    if (flag) {
                        Transaction transaction = block.getTransactions().get(block.getTransactions().size() - 1);
                        byte[] buf = transaction.getData();
                        try {
                            if (buf.length > 0)
                                userdata = new UserSettingDataInfo().parse(buf);
                        } catch (Exception e) {
                            log.warn("", e);
                        }
                    }

                }
            }
            return userdata;
        } else {
            String CONTEXT_ROOT = Main.IpAddress + "/"; // http://" +
                                                        // Main.IpAddress
            // + ":" + Main.port + "/";
            HashMap<String, String> requestParam = new HashMap<String, String>();

            List<ECKey> issuedKeys = Main.walletAppKit.wallet().walletKeys(getAesKey());

            ECKey pubKeyTo = null;
            if (issuedKeys == null || issuedKeys.isEmpty())
                return null;
            pubKeyTo = issuedKeys.get(0);

            if (DataClassName.TOKEN.name().equals(type) || DataClassName.LANG.name().equals(type)
                    || DataClassName.SERVERURL.name().equals(type)
                    || DataClassName.BlockSolveType.name().equals(type)) {
                
            }
            requestParam.put("pubKey", pubKeyTo.getPublicKeyAsHex());
            requestParam.put("dataclassname", type);
            byte[] bytes = OkHttp3Util.postAndGetBlock(CONTEXT_ROOT + ReqCmd.getUserData.name(),
                    Json.jsonmapper().writeValueAsString(requestParam));
            if (DataClassName.CONTACTINFO.name().equals(type)) {
                if (bytes == null || bytes.length == 0) {
                    return new ContactInfo();
                }
                ContactInfo contactInfo = new ContactInfo().parse(bytes);
                return contactInfo;
            } else if (DataClassName.MYHOMEADDRESS.name().equals(type)) {
                if (bytes == null || bytes.length == 0) {
                    return new MyHomeAddress();
                }
                MyHomeAddress myHomeAddress = new MyHomeAddress().parse(bytes);
                return myHomeAddress;
            } else if (DataClassName.UPLOADFILE.name().equals(type)) {
                if (bytes == null || bytes.length == 0) {
                    return new UploadfileInfo();
                }
                UploadfileInfo uploadfileInfo = new UploadfileInfo().parse(bytes);
                return uploadfileInfo;
            } else if (DataClassName.SERVERURL.name().equals(type) || DataClassName.LANG.name().equals(type)
                    || DataClassName.TOKEN.name().equals(type)  ) {
                UserSettingDataInfo userSettingDataInfo = null;

                if (bytes == null || bytes.length == 0) {
                    return new UserSettingDataInfo();
                }
                userSettingDataInfo = new UserSettingDataInfo().parse(bytes);

                return userSettingDataInfo;
            } else {
                return null;
            }
        }

    }

    public static List<String> getNoMultiTokens() throws Exception {
        List<String> tokenList = new ArrayList<String>();
        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        String CONTEXT_ROOT = Main.getContextRoot();
        requestParam.put("name", null);
       byte[] response = OkHttp3Util.post(CONTEXT_ROOT + ReqCmd.searchTokens.name(),
                Json.jsonmapper().writeValueAsString(requestParam).getBytes());
        GetTokensResponse getTokensResponse = Json.jsonmapper().readValue(response, GetTokensResponse.class);
        for (Token tokens : getTokensResponse.getTokens()) {
            String tokenHex = tokens.getTokenid();
            if (tokens.getSignnumber() >= 3) {
                continue;
            }
            if (tokens.getTokentype() != TokenType.token.ordinal()) {
                continue;
            }
            tokenList.add(tokenHex);
        }
        return tokenList;

    }
}
