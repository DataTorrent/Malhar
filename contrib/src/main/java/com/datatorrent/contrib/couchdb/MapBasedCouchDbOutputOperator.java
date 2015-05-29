/*
 * Copyright (c) 2013 DataTorrent, Inc. ALL Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.contrib.couchdb;

import java.util.Map;

/**
 * Implementation of {@link AbstractCouchDBOutputOperator} that saves a Map in the couch database. <br/>
 * <p>
 * @displayName Map Based CouchDb Output Operator
 * @category Database
 * @tags output operator
 * @since 0.3.5
 */
public class MapBasedCouchDbOutputOperator extends AbstractCouchDBOutputOperator<Map<Object, Object>>
{

  @Override
  public String getDocumentId(Map<Object, Object> tuple)
  {
    return (String) tuple.get("_id");
  }

}
