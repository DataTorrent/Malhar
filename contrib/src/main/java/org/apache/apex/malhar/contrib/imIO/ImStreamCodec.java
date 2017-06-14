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
 *
 */
package org.apache.apex.malhar.contrib.imIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.datatorrent.lib.codec.KryoSerializableStreamCodec;


public class ImStreamCodec extends KryoSerializableStreamCodec
{
  protected static final Logger LOG = LoggerFactory.getLogger(ASASSN.class);
  private int tupleNum;
  private int partitions;

  ImStreamCodec(int partitions)
  {
    this.partitions = partitions;
  }

  @Override
  public int getPartition(Object o)
  {

    int part = tupleNum % 10;
    if (part > partitions) {
      part = part - (partitions + 1);
    }
    LOG.info("TupNumIs " + part);
    tupleNum++;
    return part;
  }
}