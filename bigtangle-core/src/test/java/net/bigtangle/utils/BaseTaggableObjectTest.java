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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BaseTaggableObjectTest {
	private BaseTaggableObject obj;

	@BeforeEach
	public void setUp() throws Exception {
		obj = new BaseTaggableObject();
	}

	@Test
	public void tags() throws Exception {
		assertNull(obj.maybeGetTag("foo"));
		obj.setTag("foo", "bar".getBytes());
		assertNotNull(obj.getTag("foo"));
	}

	@Test
	public void exception() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> {
			obj.getTag("non existent");
		});

	}
}