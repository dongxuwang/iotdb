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

package org.apache.iotdb.db.query.udf.builtin;

import org.apache.iotdb.commons.exception.MetadataException;
import org.apache.iotdb.db.query.udf.api.access.Row;
import org.apache.iotdb.db.query.udf.api.collector.PointCollector;
import org.apache.iotdb.db.query.udf.api.customizer.config.UDTFConfigurations;
import org.apache.iotdb.db.query.udf.api.customizer.parameter.UDFParameters;
import org.apache.iotdb.db.query.udf.api.customizer.strategy.RowByRowAccessStrategy;
import org.apache.iotdb.db.query.udf.api.exception.UDFInputSeriesDataTypeNotValidException;

import java.io.IOException;

public abstract class UDTFValueDifference extends UDTFValueTrend {

  @Override
  public void beforeStart(UDFParameters parameters, UDTFConfigurations configurations)
      throws MetadataException {
    dataType = parameters.getDataType(0);
    configurations.setAccessStrategy(new RowByRowAccessStrategy()).setOutputDataType(dataType);
  }

  @Override
  public void transform(Row row, PointCollector collector)
      throws UDFInputSeriesDataTypeNotValidException, IOException {
    if (!hasPrevious) {
      updatePreviousValue(row);
      hasPrevious = true;
      return;
    }

    doTransform(row, collector);
  }
}
