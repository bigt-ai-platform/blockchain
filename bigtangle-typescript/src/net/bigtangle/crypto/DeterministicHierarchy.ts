/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2013 Matija Mazi.
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

import { ChildNumber } from './ChildNumber';
import { DeterministicKey } from './DeterministicKey';
import { HDKeyDerivation } from './HDKeyDerivation';
import { HDDerivationException } from './HDDerivationException';
import { HDUtils } from './HDUtils';

/**
 * <p>A DeterministicHierarchy calculates and keeps a whole tree (hierarchy) of keys originating from a single
 * root key. This implements part of the BIP 32 specification. A deterministic key tree is useful because
 * Bitcoin's privacy system require new keys to be created for each transaction, but managing all these
 * keys quickly becomes unwieldy. In particular it becomes hard to back up and distribute them. By having
 * a way to derive random-looking but deterministic keys we can make wallet backup simpler and gain the
 * ability to hand out {@link DeterministicKey}s to other people who can then create new addresses
 * on the fly, without having to contact us.</p>
 *
 * <p>The hierarchy is started from a single root key, and a location in the tree is given by a path which
 * is a list of {@link ChildNumber}s.</p>
 */
export class DeterministicHierarchy {
    private readonly keys: Map<string, DeterministicKey> = new Map();
    private readonly rootPath: ChildNumber[];
    // Keep track of how many child keys each node has. This is kind of weak.
    private readonly lastChildNumbers: Map<string, ChildNumber> = new Map();

    public static readonly BIP32_STANDARDISATION_TIME_SECS = 1369267200;

    /**
     * Constructs a new hierarchy rooted at the given key. Note that this does not have to be the top of the tree.
     * You can construct a DeterministicHierarchy for a subtree of a larger tree that you may not own.
     */
    constructor(rootKey: DeterministicKey) {
        this.putKey(rootKey);
        this.rootPath = rootKey.getPath();
    }

    /**
     * Inserts a key into the heirarchy. Used during deserialization: you normally don't need this. Keys must be
     * inserted in order.
     */
    public putKey(key: DeterministicKey): void {
        const path = key.getPath();
        // Update our tracking of what the next child in each branch of the tree should be. Just assume that keys are
        // inserted in order here.
        const parent = key.getParent();
        if (parent != null) {
            this.lastChildNumbers.set(HDUtils.formatPath(parent.getPath()), key.getChildNumber());
        }
        this.keys.set(HDUtils.formatPath(path), key);
    }

    /**
     * Returns a key for the given path, optionally creating it.
     *
     * @param path the path to the key
     * @param relativePath whether the path is relative to the root path
     * @param create whether the key corresponding to path should be created (with any necessary ancestors) if it doesn't exist already
     * @return next newly created key using the child derivation function
     * @throws IllegalArgumentException if create is false and the path was not found.
     */
    public get(path: ChildNumber[], relativePath: boolean, create: boolean): DeterministicKey {
        const absolutePath = relativePath
                ? [...this.rootPath, ...path]
                : [...path];
        const absolutePathStr = HDUtils.formatPath(absolutePath);

        if (!this.keys.has(absolutePathStr)) {
            if (!create) {
                throw new Error(`No key found for ${relativePath ? "relative" : "absolute"} path ${HDUtils.formatPath(path)}.`);
            }
            if (absolutePath.length === 0) {
                throw new Error("Can't derive the master key: nothing to derive from.");
            }
            const parentPath = absolutePath.slice(0, absolutePath.length - 1);
            const parent = this.get(parentPath, false, true);
            this.putKey(HDKeyDerivation.deriveChildKey(parent, absolutePath[absolutePath.length - 1]));
        }
        return this.keys.get(absolutePathStr)!;
    }

    /**
     * Extends the tree by calculating the next key that hangs off the given parent path. For example, if you pass a
     * path of 1/2 here and there are already keys 1/2/1 and 1/2/2 then it will derive 1/2/3.
     *
     * @param parentPath the path to the parent
     * @param relative whether the path is relative to the root path
     * @param createParent whether the parent corresponding to path should be created (with any necessary ancestors) if it doesn't exist already
     * @param privateDerivation whether to use private or public derivation
     * @return next newly created key using the child derivation funtcion
     * @throws IllegalArgumentException if the parent doesn't exist and createParent is false.
     */
    public deriveNextChild(parentPath: ChildNumber[], relative: boolean, createParent: boolean, privateDerivation: boolean): DeterministicKey {
        const parent = this.get(parentPath, relative, createParent);
        let nAttempts = 0;
        while (nAttempts++ < HDKeyDerivation.MAX_CHILD_DERIVATION_ATTEMPTS) {
            try {
                const createChildNumber = this.getNextChildNumberToDerive(parent.getPath(), privateDerivation);
                return this.deriveChild(parent, createChildNumber);
            } catch (ignore) { 
                if (ignore instanceof HDDerivationException) {
                    // continue
                } else {
                    throw ignore;
                }
            }
        }
        throw new HDDerivationException("Maximum number of child derivation attempts reached, this is probably an indication of a bug.");
    }

    private getNextChildNumberToDerive(path: ChildNumber[], privateDerivation: boolean): ChildNumber {
        const lastChildNumber = this.lastChildNumbers.get(HDUtils.formatPath(path));
        const nextChildNumber = new ChildNumber(lastChildNumber != null ? lastChildNumber.num() + 1 : 0, privateDerivation);
        this.lastChildNumbers.set(HDUtils.formatPath(path), nextChildNumber);
        return nextChildNumber;
    }

    public getNumChildren(path: ChildNumber[]): number {
        const cn = this.lastChildNumbers.get(HDUtils.formatPath(path));
        if (cn == null) {
            return 0;
        } else {
            return cn.num() + 1;   // children start with zero based childnumbers
        }
    }

    /**
     * Extends the tree by calculating the requested child for the given path. For example, to get the key at position
     * 1/2/3 you would pass 1/2 as the parent path and 3 as the child number.
     *
     * @param parentPath the path to the parent
     * @param relative whether the path is relative to the root path
     * @param createParent whether the parent corresponding to path should be created (with any necessary ancestors) if it doesn't exist already
     * @return the requested key.
     * @throws IllegalArgumentException if the parent doesn't exist and createParent is false.
     */
    public deriveChildFromPath(parentPath: ChildNumber[], relative: boolean, createParent: boolean, createChildNumber: ChildNumber): DeterministicKey {
        return this.deriveChild(this.get(parentPath, relative, createParent), createChildNumber);
    }

    private deriveChild(parent: DeterministicKey, createChildNumber: ChildNumber): DeterministicKey {
        const childKey = HDKeyDerivation.deriveChildKey(parent, createChildNumber);
        this.putKey(childKey);
        return childKey;
    }

    /**
     * Returns the root key that the {@link DeterministicHierarchy} was created with.
     */
    public getRootKey(): DeterministicKey {
        return this.get(this.rootPath, false, false);
    }
}