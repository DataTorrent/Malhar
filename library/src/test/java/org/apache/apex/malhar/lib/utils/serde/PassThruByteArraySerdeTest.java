/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.apex.malhar.lib.utils.serde;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import org.apache.commons.lang3.mutable.MutableInt;

public class PassThruByteArraySerdeTest
{
  @Rule
  public SerdeByteArrayToByteArrayTestWatcher testMeta = new SerdeByteArrayToByteArrayTestWatcher();

  public static class SerdeByteArrayToByteArrayTestWatcher extends TestWatcher
  {
    public PassThruByteArraySerde serde;

    @Override
    protected void starting(Description description)
    {
      this.serde = new PassThruByteArraySerde();
      super.starting(description);
    }
  }

  @Test
  public void simpleSerializeTest()
  {
    byte[] byteArray = new byte[]{1, 2, 3};
    SerializationBuffer buffer = new DefaultSerializationBuffer(new WindowedBlockStream());
    testMeta.serde.serialize(byteArray, buffer);

    Assert.assertArrayEquals(byteArray, buffer.toSlice().toByteArray());
  }

  @Test
  public void simpleDeserializeTest()
  {
    byte[] byteArray = new byte[]{1, 2, 3};
    byte[] serialized = testMeta.serde.deserialize(byteArray, new MutableInt(0), byteArray.length);

    Assert.assertArrayEquals(byteArray, serialized);
  }

  @Test
  public void simpleDeserializeOffsetTest()
  {
    byte[] byteArray = new byte[]{1, 2, 3};
    byte[] serialized = testMeta.serde.deserialize(byteArray, new MutableInt(0), byteArray.length);

    Assert.assertArrayEquals(byteArray, serialized);
  }
}
