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

package org.apache.iotdb.cluster.query.last;

import org.apache.iotdb.cluster.ClusterIoTDB;
import org.apache.iotdb.cluster.client.async.AsyncDataClient;
import org.apache.iotdb.cluster.client.sync.SyncClientAdaptor;
import org.apache.iotdb.cluster.client.sync.SyncDataClient;
import org.apache.iotdb.cluster.config.ClusterConstant;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.exception.CheckConsistencyException;
import org.apache.iotdb.cluster.partition.PartitionGroup;
import org.apache.iotdb.cluster.rpc.thrift.LastQueryRequest;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.server.member.DataGroupMember;
import org.apache.iotdb.cluster.server.member.MetaGroupMember;
import org.apache.iotdb.commons.concurrent.IoTDBThreadPoolFactory;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.commons.utils.SerializeUtils;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.qp.physical.crud.LastQueryPlan;
import org.apache.iotdb.db.qp.physical.crud.RawDataQueryPlan;
import org.apache.iotdb.db.query.context.QueryContext;
import org.apache.iotdb.db.query.executor.LastQueryExecutor;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.TimeValuePair;
import org.apache.iotdb.tsfile.read.expression.IExpression;
import org.apache.iotdb.tsfile.read.expression.impl.GlobalTimeExpression;
import org.apache.iotdb.tsfile.read.filter.basic.Filter;

import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class ClusterLastQueryExecutor extends LastQueryExecutor {

  private static final Logger logger = LoggerFactory.getLogger(ClusterLastQueryExecutor.class);
  private MetaGroupMember metaGroupMember;

  private static ExecutorService lastQueryPool =
      IoTDBThreadPoolFactory.newFixedThreadPool(
          Runtime.getRuntime().availableProcessors(), "ClusterLastQuery");

  public ClusterLastQueryExecutor(LastQueryPlan lastQueryPlan, MetaGroupMember metaGroupMember) {
    super(lastQueryPlan);
    this.metaGroupMember = metaGroupMember;
  }

  @Override
  protected List<TimeValuePair> calculateLastPairForSeries(
      List<PartialPath> seriesPaths,
      List<TSDataType> dataTypes,
      QueryContext context,
      IExpression expression,
      RawDataQueryPlan lastQueryPlan)
      throws QueryProcessException, IOException {
    return calculateLastPairsForSeries(seriesPaths, dataTypes, context, expression, lastQueryPlan);
  }

  private List<TimeValuePair> calculateLastPairsForSeries(
      List<PartialPath> seriesPaths,
      List<TSDataType> dataTypes,
      QueryContext context,
      IExpression expression,
      RawDataQueryPlan lastQueryPlan)
      throws IOException, QueryProcessException {
    // calculate the global last from all data groups
    try {
      metaGroupMember.syncLeaderWithConsistencyCheck(false);
    } catch (CheckConsistencyException e) {
      throw new IOException(e);
    }
    List<TimeValuePair> results = new ArrayList<>(seriesPaths.size());
    for (int i = 0; i < seriesPaths.size(); i++) {
      results.add(null);
    }
    List<PartitionGroup> globalGroups = metaGroupMember.getPartitionTable().getGlobalGroups();
    List<Future<List<TimeValuePair>>> groupFutures = new ArrayList<>(globalGroups.size());
    List<Integer> dataTypeOrdinals = new ArrayList<>(dataTypes.size());
    for (TSDataType dataType : dataTypes) {
      dataTypeOrdinals.add(dataType.ordinal());
    }
    for (PartitionGroup globalGroup : globalGroups) {
      GroupLastTask task =
          new GroupLastTask(
              globalGroup,
              seriesPaths,
              dataTypes,
              context,
              expression,
              lastQueryPlan,
              dataTypeOrdinals);
      groupFutures.add(lastQueryPool.submit(task));
    }
    for (Future<List<TimeValuePair>> groupFuture : groupFutures) {
      try {
        // merge results from each group
        List<TimeValuePair> timeValuePairs = groupFuture.get();
        for (int i = 0; i < timeValuePairs.size(); i++) {
          TimeValuePair currentResult = results.get(i);
          TimeValuePair newResult = timeValuePairs.get(i);
          if (currentResult == null
              || newResult != null && newResult.getTimestamp() > currentResult.getTimestamp()) {
            results.add(i, newResult);
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.warn("Query last of {} interrupted", seriesPaths);
      } catch (ExecutionException e) {
        throw new QueryProcessException(e, TSStatusCode.QUERY_PROCESS_ERROR.getStatusCode());
      }
    }
    return results;
  }

  class GroupLastTask implements Callable<List<TimeValuePair>> {

    private PartitionGroup group;
    private List<PartialPath> seriesPaths;
    private List<TSDataType> dataTypes;
    private List<Integer> dataTypeOrdinals;
    private QueryContext queryContext;
    private RawDataQueryPlan queryPlan;
    private IExpression expression;

    GroupLastTask(
        PartitionGroup group,
        List<PartialPath> seriesPaths,
        List<TSDataType> dataTypes,
        QueryContext context,
        IExpression expression,
        RawDataQueryPlan lastQueryPlan,
        List<Integer> dataTypeOrdinals) {
      this.group = group;
      this.seriesPaths = seriesPaths;
      this.dataTypes = dataTypes;
      this.queryContext = context;
      this.queryPlan = lastQueryPlan;
      this.expression = expression;
      this.dataTypeOrdinals = dataTypeOrdinals;
    }

    @Override
    public List<TimeValuePair> call() throws Exception {
      return calculateSeriesLast(group, seriesPaths, queryContext);
    }

    private List<TimeValuePair> calculateSeriesLast(
        PartitionGroup group, List<PartialPath> seriesPaths, QueryContext context)
        throws QueryProcessException, StorageEngineException, IOException {
      if (group.contains(metaGroupMember.getThisNode())) {
        return calculateSeriesLastLocally(group, seriesPaths, context);
      } else {
        return calculateSeriesLastRemotely(group, seriesPaths, context);
      }
    }

    private List<TimeValuePair> calculateSeriesLastLocally(
        PartitionGroup group, List<PartialPath> seriesPaths, QueryContext context)
        throws StorageEngineException, QueryProcessException, IOException {
      DataGroupMember localDataMember =
          metaGroupMember.getLocalDataMember(group.getHeader(), group.getRaftId());
      try {
        localDataMember.syncLeaderWithConsistencyCheck(false);
      } catch (CheckConsistencyException e) {
        throw new QueryProcessException(e.getMessage());
      }
      return calculateLastPairForSeriesLocally(
          seriesPaths, dataTypes, context, expression, queryPlan.getDeviceToMeasurements());
    }

    private List<TimeValuePair> calculateSeriesLastRemotely(
        PartitionGroup group, List<PartialPath> seriesPaths, QueryContext context) {
      for (Node node : group) {
        try {
          ByteBuffer buffer;
          if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
            buffer = lastAsync(node, context);
          } else {
            buffer = lastSync(node, context);
          }
          if (buffer == null) {
            continue;
          }

          List<TimeValuePair> timeValuePairs = new ArrayList<>();
          for (int i = 0; i < seriesPaths.size(); i++) {
            timeValuePairs.add(SerializeUtils.deserializeTVPair(buffer));
          }
          List<TimeValuePair> results = new ArrayList<>();
          for (int i = 0; i < seriesPaths.size(); i++) {
            TimeValuePair pair = timeValuePairs.get(i);
            results.add(pair);
          }
          return results;
        } catch (IOException | TException e) {
          logger.warn("Query last of {} from {} errored", group, seriesPaths, e);
          return Collections.emptyList();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          logger.warn("Query last of {} from {} interrupted", group, seriesPaths, e);
          return Collections.emptyList();
        }
      }
      return Collections.emptyList();
    }

    private ByteBuffer lastAsync(Node node, QueryContext context)
        throws IOException, TException, InterruptedException {
      ByteBuffer buffer;
      AsyncDataClient asyncDataClient =
          ClusterIoTDB.getInstance()
              .getAsyncDataClient(node, ClusterConstant.getReadOperationTimeoutMS());
      if (asyncDataClient == null) {
        return null;
      }
      Filter timeFilter =
          (expression == null) ? null : ((GlobalTimeExpression) expression).getFilter();
      buffer =
          SyncClientAdaptor.last(
              asyncDataClient,
              seriesPaths,
              dataTypeOrdinals,
              timeFilter,
              context,
              queryPlan.getDeviceToMeasurements(),
              group.getHeader());
      return buffer;
    }

    private ByteBuffer lastSync(Node node, QueryContext context) throws IOException, TException {
      ByteBuffer res;
      SyncDataClient syncDataClient = null;
      try {
        syncDataClient =
            ClusterIoTDB.getInstance()
                .getSyncDataClient(node, ClusterConstant.getReadOperationTimeoutMS());
        LastQueryRequest lastQueryRequest =
            new LastQueryRequest(
                PartialPath.toStringList(seriesPaths),
                dataTypeOrdinals,
                context.getQueryId(),
                queryPlan.getDeviceToMeasurements(),
                group.getHeader(),
                syncDataClient.getNode());
        Filter timeFilter =
            (expression == null) ? null : ((GlobalTimeExpression) expression).getFilter();
        if (timeFilter != null) {
          lastQueryRequest.setFilterBytes(SerializeUtils.serializeFilter(timeFilter));
        }
        res = syncDataClient.last(lastQueryRequest);
      } catch (IOException | TException e) {
        // the connection may be broken, close it to avoid it being reused
        if (syncDataClient != null) {
          syncDataClient.close();
        }
        throw e;
      } finally {
        if (syncDataClient != null) {
          syncDataClient.returnSelf();
        }
      }
      return res;
    }
  }
}
