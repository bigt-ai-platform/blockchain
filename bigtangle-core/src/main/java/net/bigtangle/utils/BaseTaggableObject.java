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

package net.bigtangle.utils;

import com.google.common.collect.Maps;

import jakarta.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A simple implementation of {@link TaggableObject} that just uses a lazily created hashmap that is
 * synchronized on this objects Java monitor.
 */
public class BaseTaggableObject implements TaggableObject {
    @Nullable protected Map<String, byte[]> tags;

    /** {@inheritDoc} */
    @Override
    @Nullable
    public synchronized byte[] maybeGetTag(String tag) {
        if (tags == null)
            return null;
        else
            return tags.get(tag);
    }

    /** {@inheritDoc} */
    @Override
    public byte[] getTag(String tag) {
        byte[] b = maybeGetTag(tag);
        if (b == null)
            throw new IllegalArgumentException("Unknown tag " + tag);
        return b;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void setTag(String tag, byte[] value) {
        checkNotNull(tag);
        checkNotNull(value);
        if (tags == null)
            tags = new HashMap<String, byte[]>();
        tags.put(tag, value);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Map<String, byte[]> getTags() {
        if (tags != null)
            return Maps.newHashMap(tags);
        else
            return Maps.newHashMap();
    }
}
