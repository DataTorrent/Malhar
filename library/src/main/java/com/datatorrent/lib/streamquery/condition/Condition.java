/*
 * Copyright (c) 2013 DataTorrent, Inc. ALL Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.lib.streamquery.condition;

import java.util.Map;

import javax.validation.constraints.NotNull;

/**
 * A base class for select condition for selecting or filtering the rows.&nbsp; Subclasses should provide the 
   implementation to filter valid rows and valid joins.
 * <p>
 * @displayName Condition
 * @category Streamquery/Condition
 * @tags sql condition, filter
 * @since 0.3.3
 */
abstract public class Condition
{
	/**
	 * Row containing column/value map.
	 * @return row validation status.
	 */
  abstract public boolean isValidRow(@NotNull Map<String, Object> row);
  
  /**
   * Filter valid rows only.
   */
  public abstract boolean isValidJoin(@NotNull Map<String, Object> row1, Map<String, Object> row2);
}