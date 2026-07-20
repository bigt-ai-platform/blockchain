/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2014 devrandom
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

import net.bigtangle.crypto.DeterministicKey;
import net.bigtangle.crypto.KeyCrypter;

/**
 * Factory interface for creation keychains while de-serializing a wallet.
 */
public interface KeyChainFactory {
    DeterministicKeyChain makeKeyChain(byte[] key, byte[] firstSubKey, DeterministicSeed seed, KeyCrypter crypter, boolean isMarried);

    DeterministicKeyChain makeWatchingKeyChain(byte[] key, byte[] firstSubKey, DeterministicKey accountKey, boolean isFollowingKey, boolean isMarried) throws UnreadableWalletException;
}
