/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.query.udf.core.layer;

import org.apache.iotdb.db.mpp.execution.operator.Operator;
import org.apache.iotdb.db.query.dataset.IUDFInputDataSet;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.block.TsBlock.TsBlockRowIterator;

import java.util.List;

public class TsBlockInputDataSet implements IUDFInputDataSet {

  private final Operator operator;
  private final List<TSDataType> dataTypes;

  private TsBlockRowIterator tsBlockRowIterator;

  public TsBlockInputDataSet(Operator operator, List<TSDataType> dataTypes) {
    this.operator = operator;
    this.dataTypes = dataTypes;
  }

  @Override
  public List<TSDataType> getDataTypes() {
    return dataTypes;
  }

  @Override
  public boolean hasNextRowInObjects() {
    if (tsBlockRowIterator != null && tsBlockRowIterator.hasNext()) {
      return true;
    }

    if (!operator.hasNext()) {
      return false;
    }

    tsBlockRowIterator = operator.next().getTsBlockRowIterator();
    return true;
  }

  @Override
  public Object[] nextRowInObjects() {
    return tsBlockRowIterator.next();
  }
}
