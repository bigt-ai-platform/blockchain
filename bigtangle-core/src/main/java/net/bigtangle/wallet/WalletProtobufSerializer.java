/*******************************************************************************
	 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2012 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
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

package net.bigtangle.wallet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.TextFormat;
import com.google.protobuf.WireFormat;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.crypto.KeyCrypter;
import net.bigtangle.crypto.KeyCrypterScrypt;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.wallet.Protos.Wallet.EncryptionType;

/**
 * Serialize and de-serialize a wallet to a byte stream containing a <a href=
 * "https://developers.google.com/protocol-buffers/docs/overview">protocol
 * buffer</a>. Protocol buffers are a data interchange format developed by
 * Google with an efficient binary representation, a type safe specification
 * language and compilers that generate code to work with those data structures
 * for many languages. Protocol buffers can have their format evolved over time:
 * conceptually they represent data using (tag, length, value) tuples. The
 * format is defined by the <tt>wallet.proto</tt> file in the bitcoinj source
 * distribution.
 * <p>
 *
 * This class is used through its static methods. The most common operations are
 * writeWallet and readWallet, which do the obvious operations on
 * Output/InputStreams. You can use a {@link java.io.ByteArrayInputStream} and
 * equivalent {@link java.io.ByteArrayOutputStream} if you'd like byte arrays
 * instead. The protocol buffer can also be manipulated in its object form if
 * you'd like to modify the flattened data structure before serialization to
 * binary.
 * <p>
 *
 * You can extend the wallet format with additional fields specific to your
 * application if you want, but make sure to either put the extra data in the
 * provided extension areas, or select tag numbers that are unlikely to be used
 * by anyone else.
 * <p>
 *
 * @author Miron Cuperman
 * @author Andreas Schildbach
 */
public class WalletProtobufSerializer {
   // private static final Logger log = LoggerFactory.getLogger(WalletProtobufSerializer.class);
    /**
     * Current version used for serializing wallets. A version higher than this
     * is considered from the future.
     */
    public static final int CURRENT_WALLET_VERSION = Protos.Wallet.getDefaultInstance().getVersion();
    // 1 MB
    private static final int WALLET_SIZE_LIMIT =  1024 * 1024;

   
    public interface WalletFactory {
        Wallet create(NetworkParameters params, KeyChainGroup keyChainGroup);
    }

    private final WalletFactory factory;
    private KeyChainFactory keyChainFactory;

    public WalletProtobufSerializer() {
        this(new WalletFactory() {
            @Override
            public Wallet create(NetworkParameters params, KeyChainGroup keyChainGroup) {
                return new Wallet(params, keyChainGroup);
            }
        });
    }

    public WalletProtobufSerializer(WalletFactory factory) {
      
        this.factory = factory;
        this.keyChainFactory = new DefaultKeyChainFactory();
    }

    public void setKeyChainFactory(KeyChainFactory keyChainFactory) {
        this.keyChainFactory = keyChainFactory;
    }



    /**
     * Formats the given wallet (transactions and keys) to the given output stream in protocol buffer format.<p>
     *
     * Equivalent to <tt>walletToProto(wallet).writeTo(output);</tt>
     */
    public void writeWallet(Wallet wallet, OutputStream output) throws IOException {
        Protos.Wallet walletProto = walletToProto(wallet);
        walletProto.writeTo(output);
    }

    /**
     * Returns the given wallet formatted as text. The text format is that used by protocol buffers and although it
     * can also be parsed using {@link TextFormat#merge(CharSequence, com.google.protobuf.Message.Builder)},
     * it is designed more for debugging than storage. It is not well specified and wallets are largely binary data
     * structures anyway, consisting as they do of keys (large random numbers) and {@link Transaction}s which also
     * mostly contain keys and hashes.
     */
    public String walletToText(Wallet wallet) {
        Protos.Wallet walletProto = walletToProto(wallet);
        return TextFormat.printToString(walletProto);
    }

    /**
     * Converts the given wallet to the object representation of the protocol
     * buffers. This can be modified, or additional data fields set, before
     * serialization takes place.
     */
    public Protos.Wallet walletToProto(Wallet wallet) {
        Protos.Wallet.Builder walletBuilder = Protos.Wallet.newBuilder();
        walletBuilder.setNetworkIdentifier(wallet.getNetworkParameters().getId());
     
        walletBuilder.addAllKey(wallet.serializeKeyChainGroupToProtobuf());

        // Populate the scrypt parameters.
        KeyCrypter keyCrypter = wallet.getKeyCrypter();
        if (keyCrypter == null) {
            // The wallet is unencrypted.
            walletBuilder.setEncryptionType(EncryptionType.UNENCRYPTED);
        } else {
            // The wallet is encrypted.
            walletBuilder.setEncryptionType(keyCrypter.getUnderstoodEncryptionType());
            if (keyCrypter instanceof KeyCrypterScrypt) {
                KeyCrypterScrypt keyCrypterScrypt = (KeyCrypterScrypt) keyCrypter;
                walletBuilder.setEncryptionParameters(keyCrypterScrypt.getScryptParameters());
            } else {
                // Some other form of encryption has been specified that we do
                // not know how to persist.
                throw new RuntimeException(
                        "The wallet has encryption of type '" + keyCrypter.getUnderstoodEncryptionType()
                                + "' but this WalletProtobufSerializer does not know how to persist this.");
            }
        }

   
        for (Map.Entry<String, ByteString> entry : wallet.getTags().entrySet()) {
            Protos.Tag.Builder tag = Protos.Tag.newBuilder().setTag(entry.getKey()).setData(entry.getValue());
            walletBuilder.addTags(tag);
        }

        return walletBuilder.build();
    }

    
 

    public static ByteString hashToByteString(Sha256Hash hash) {
        return ByteString.copyFrom(hash.getBytes());
    }

    public static Sha256Hash byteStringToHash(ByteString bs) {
        return Sha256Hash.wrap(bs.toByteArray());
    }

    /**
     * <p>
     * Loads wallet data from the given protocol buffer and inserts it into the
     * given Wallet object. This is primarily useful when you wish to
     * pre-register extension objects. Note that if loading fails the provided
     * Wallet object may be in an indeterminate state and should be thrown away.
     * </p>
     *
     * <p>
     * A wallet can be unreadable for various reasons, such as inability to open
     * the file, corrupt data, internally inconsistent data, a wallet extension
     * marked as mandatory that cannot be handled and so on. You should always
     * handle {@link UnreadableWalletException} and communicate failure to the
     * user in an appropriate manner.
     * </p>
     *
     * @throws UnreadableWalletException
     *             thrown in various error conditions (see description).
     */
    public Wallet readWallet(InputStream input, @Nullable WalletExtension... walletExtensions)
            throws UnreadableWalletException {
        return readWallet(input, false, walletExtensions);
    }

    /**
     * <p>
     * Loads wallet data from the given protocol buffer and inserts it into the
     * given Wallet object. This is primarily useful when you wish to
     * pre-register extension objects. Note that if loading fails the provided
     * Wallet object may be in an indeterminate state and should be thrown away.
     * Do not simply call this method again on the same Wallet object with
     * {@code forceReset} set {@code true}. It won't work.
     * </p>
     *
     * <p>
     * If {@code forceReset} is {@code true}, then no transactions are loaded
     * from the wallet, and it is configured to replay transactions from the
     * blockchain (as if the wallet had been loaded and {@link Wallet.reset} had
     * been called immediately thereafter).
     *
     * <p>
     * A wallet can be unreadable for various reasons, such as inability to open
     * the file, corrupt data, internally inconsistent data, a wallet extension
     * marked as mandatory that cannot be handled and so on. You should always
     * handle {@link UnreadableWalletException} and communicate failure to the
     * user in an appropriate manner.
     * </p>
     *
     * @throws UnreadableWalletException
     *             thrown in various error conditions (see description).
     */
    public Wallet readWallet(InputStream input, boolean forceReset, @Nullable WalletExtension[] extensions)
            throws UnreadableWalletException {
        try {
            Protos.Wallet walletProto = parseToProto(input);
            final String paramsID = walletProto.getNetworkIdentifier();
            NetworkParameters params = NetworkParameters.fromID(paramsID);
            if (params == null)
                throw new UnreadableWalletException("Unknown network parameters ID " + paramsID);
            return readWallet(params, extensions, walletProto, forceReset);
        } catch (IOException e) {
            throw new UnreadableWalletException("Could not parse input stream to protobuf", e);
        } catch (IllegalStateException e) {
            throw new UnreadableWalletException("Could not parse input stream to protobuf", e);
        } catch (IllegalArgumentException e) {
            throw new UnreadableWalletException("Could not parse input stream to protobuf", e);
        }
    }

    /**
     * <p>
     * Loads wallet data from the given protocol buffer and inserts it into the
     * given Wallet object. This is primarily useful when you wish to
     * pre-register extension objects. Note that if loading fails the provided
     * Wallet object may be in an indeterminate state and should be thrown away.
     * </p>
     *
     * <p>
     * A wallet can be unreadable for various reasons, such as inability to open
     * the file, corrupt data, internally inconsistent data, a wallet extension
     * marked as mandatory that cannot be handled and so on. You should always
     * handle {@link UnreadableWalletException} and communicate failure to the
     * user in an appropriate manner.
     * </p>
     *
     * @throws UnreadableWalletException
     *             thrown in various error conditions (see description).
     */
    public Wallet readWallet(NetworkParameters params, @Nullable WalletExtension[] extensions,
            Protos.Wallet walletProto) throws UnreadableWalletException {
        return readWallet(params, extensions, walletProto, false);
    }

    /**
     * <p>
     * Loads wallet data from the given protocol buffer and inserts it into the
     * given Wallet object. This is primarily useful when you wish to
     * pre-register extension objects. Note that if loading fails the provided
     * Wallet object may be in an indeterminate state and should be thrown away.
     * Do not simply call this method again on the same Wallet object with
     * {@code forceReset} set {@code true}. It won't work.
     * </p>
     *
     * <p>
     * If {@code forceReset} is {@code true}, then no transactions are loaded
     * from the wallet, and it is configured to replay transactions from the
     * blockchain (as if the wallet had been loaded and {@link Wallet.reset} had
     * been called immediately thereafter).
     *
     * <p>
     * A wallet can be unreadable for various reasons, such as inability to open
     * the file, corrupt data, internally inconsistent data, a wallet extension
     * marked as mandatory that cannot be handled and so on. You should always
     * handle {@link UnreadableWalletException} and communicate failure to the
     * user in an appropriate manner.
     * </p>
     *
     * @throws UnreadableWalletException
     *             thrown in various error conditions (see description).
     */
    public Wallet readWallet(NetworkParameters params, @Nullable WalletExtension[] extensions,
            Protos.Wallet walletProto, boolean forceReset) throws UnreadableWalletException {
        if (walletProto.getVersion() > CURRENT_WALLET_VERSION)
            throw new UnreadableWalletException.FutureVersion();
//        if (!walletProto.getNetworkIdentifier().equals(params.getId()))
//            throw new UnreadableWalletException.WrongNetwork();

        // Read the scrypt parameters that specify how encryption and decryption
        // is performed.
        KeyChainGroup keyChainGroup;
        if (walletProto.hasEncryptionParameters()) {
            Protos.ScryptParameters encryptionParameters = walletProto.getEncryptionParameters();
            final KeyCrypterScrypt keyCrypter = new KeyCrypterScrypt(encryptionParameters);
            keyChainGroup = KeyChainGroup.fromProtobufEncrypted(params, walletProto.getKeyList(), keyCrypter,
                    keyChainFactory);
        } else {
            keyChainGroup = KeyChainGroup.fromProtobufUnencrypted(params, walletProto.getKeyList(), keyChainFactory);
        }
        Wallet wallet = factory.create(params, keyChainGroup);

      
 
        if (walletProto.hasVersion()) {
            wallet.setVersion(walletProto.getVersion());
        }

        return wallet;
    }



    /**
     * Returns the loaded protocol buffer from the given byte stream. You
     * normally want
     * {@link Wallet#loadFromFile(java.io.File, WalletExtension...)} instead -
     * this method is designed for low level work involving the wallet file
     * format itself.
     */
    public static Protos.Wallet parseToProto(InputStream input) throws IOException {
        CodedInputStream codedInput = CodedInputStream.newInstance(input);
        codedInput.setSizeLimit(WALLET_SIZE_LIMIT);
        return Protos.Wallet.parseFrom(codedInput);
    }

    /**
     * Cheap test to see if input stream is a wallet. This checks for a magic
     * value at the beginning of the stream.
     *
     * @param is
     *            input stream to test
     * @return true if input stream is a wallet
     */
    public static boolean isWallet(InputStream is) {
        try {
            final CodedInputStream cis = CodedInputStream.newInstance(is);
            final int tag = cis.readTag();
            final int field = WireFormat.getTagFieldNumber(tag);
            if (field != 1) // network_identifier
                return false;
            final String network = cis.readString();
            return NetworkParameters.fromID(network) != null;
        } catch (IOException x) {
            return false;
        }
    }
}
