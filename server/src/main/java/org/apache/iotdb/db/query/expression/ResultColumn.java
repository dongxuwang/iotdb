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

package org.apache.iotdb.db.query.expression;

import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.db.exception.query.LogicalOptimizeException;
import org.apache.iotdb.db.qp.utils.WildcardsRemover;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.utils.ReadWriteIOUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code ResultColumn} is used to represent a result column of a query.
 *
 * <p>Assume that we have time series in db as follows: <br>
 * [ root.sg.d.a, root.sg.d.b, root.sg.e.a, root.sg.e.b ]
 *
 * <ul>
 *   Example 1: select a, a + b, udf(udf(b)) from root.sg.d, root.sg.e;
 *   <li>Step 1: constructed by ASTVisitor in StatementGenerator: <br>
 *       result columns: <br>
 *       [a, a + b, udf(udf(b))]
 *   <li>Step 2: concatenated with prefix paths:<br>
 *       result columns: <br>
 *       [root.sg.d.a, root.sg.e.a, root.sg.d.a + root.sg.d.b, root.sg.d.a + root.sg.e.b,
 *       root.sg.e.a + root.sg.d.b, root.sg.e.a + root.sg.e.b, udf(udf(root.sg.d.b)),
 *       udf(udf(root.sg.e.b))]
 *   <li>Step 3: remove wildcards:<br>
 *       result columns: <br>
 *       [root.sg.d.a, root.sg.e.a, root.sg.d.a + root.sg.d.b, root.sg.d.a + root.sg.e.b,
 *       root.sg.e.a + root.sg.d.b, root.sg.e.a + root.sg.e.b, udf(udf(root.sg.d.b)),
 *       udf(udf(root.sg.e.b))]
 * </ul>
 *
 * <ul>
 *   Example 2: select *, a + *, udf(udf(*)) from root.sg.d;
 *   <li>Step 1: constructed by ASTVisitor in StatementGenerator: <br>
 *       result columns: <br>
 *       [*, a + * , udf(udf(*))]
 *   <li>Step 2: concatenated with prefix paths:<br>
 *       result columns: <br>
 *       [root.sg.d.*, root.sg.d.a + root.sg.d.*, udf(udf(root.sg.d.*))]
 *   <li>Step 3: remove wildcards:<br>
 *       result columns: <br>
 *       [root.sg.d.a, root.sg.d.b, root.sg.d.a + root.sg.d.a, root.sg.d.a + root.sg.d.b,
 *       udf(udf(root.sg.d.a)), udf(udf(root.sg.d.b))]
 * </ul>
 */
public class ResultColumn {

  private final Expression expression;
  private final String alias;

  private TSDataType dataType;

  private List<PartialPath> allPathsInExpression;

  public ResultColumn(Expression expression, String alias) {
    this.expression = expression;
    this.alias = alias;
  }

  public ResultColumn(Expression expression) {
    this.expression = expression;
    alias = null;
  }

  public ResultColumn(ByteBuffer byteBuffer) {
    expression = Expression.deserialize(byteBuffer);
    alias = ReadWriteIOUtils.readString(byteBuffer);
    dataType = TSDataType.deserializeFrom(byteBuffer);
  }

  /**
   * @param prefixPaths prefix paths in the from clause
   * @param resultColumns used to collect the result columns
   * @param needAliasCheck used to skip illegal alias judgement here. Including !isGroupByLevel
   *     because count(*) may be * unfolded to more than one expression, but it still can be
   *     aggregated together later.
   */
  public void concat(
      List<PartialPath> prefixPaths, List<ResultColumn> resultColumns, boolean needAliasCheck)
      throws LogicalOptimizeException {
    List<Expression> resultExpressions = new ArrayList<>();
    expression.concat(prefixPaths, resultExpressions);
    if (needAliasCheck && 1 < resultExpressions.size()) {
      throw new LogicalOptimizeException(
          String.format("alias '%s' can only be matched with one time series", alias));
    }
    for (Expression resultExpression : resultExpressions) {
      resultColumns.add(new ResultColumn(resultExpression, alias));
    }
  }

  /**
   * @param wildcardsRemover used to remove wildcards from {@code expression} and apply slimit &
   *     soffset control
   * @param resultColumns used to collect the result columns
   * @param needAliasCheck used to skip illegal alias judgement here. Including !isGroupByLevel
   *     because count(*) may be * unfolded to more than one expression, but it still can be
   *     aggregated together later.
   */
  public void removeWildcards(
      WildcardsRemover wildcardsRemover, List<ResultColumn> resultColumns, boolean needAliasCheck)
      throws LogicalOptimizeException {
    List<Expression> resultExpressions = new ArrayList<>();
    expression.removeWildcards(wildcardsRemover, resultExpressions);
    if (needAliasCheck && 1 < resultExpressions.size()) {
      throw new LogicalOptimizeException(
          String.format("alias '%s' can only be matched with one time series", alias));
    }
    for (Expression resultExpression : resultExpressions) {
      resultColumns.add(new ResultColumn(resultExpression, alias));
    }
  }

  public List<PartialPath> collectPaths() {
    if (allPathsInExpression == null) {
      Set<PartialPath> pathSet = new HashSet<>();
      expression.collectPaths(pathSet);
      allPathsInExpression = new ArrayList<>(pathSet);
    }
    return allPathsInExpression;
  }

  public Expression getExpression() {
    return expression;
  }

  public boolean hasAlias() {
    return alias != null;
  }

  public String getAlias() {
    return alias;
  }

  public String getResultColumnName() {
    return alias != null ? alias : expression.getExpressionString();
  }

  public String getExpressionString() {
    return expression.getExpressionString();
  }

  public void setDataType(TSDataType dataType) {
    this.dataType = dataType;
  }

  public TSDataType getDataType() {
    return dataType;
  }

  @Override
  public String toString() {
    return "ResultColumn{" + "expression=" + expression + ", alias='" + alias + '\'' + '}';
  }

  @Override
  public final int hashCode() {
    return alias == null ? getResultColumnName().hashCode() : alias.hashCode();
  }

  @Override
  public final boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof ResultColumn)) {
      return false;
    }
    return getResultColumnName().equals(((ResultColumn) o).getResultColumnName());
  }

  public static void serialize(ResultColumn resultColumn, ByteBuffer byteBuffer) {
    Expression.serialize(resultColumn.expression, byteBuffer);
    ReadWriteIOUtils.write(resultColumn.alias, byteBuffer);
    resultColumn.dataType.serializeTo(byteBuffer);
  }

  public static ResultColumn deserialize(ByteBuffer byteBuffer) {
    return new ResultColumn(byteBuffer);
  }
}
