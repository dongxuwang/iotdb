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

package org.apache.iotdb.db.mpp.plan.scheduler;

import org.apache.iotdb.common.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.commons.client.IClientManager;
import org.apache.iotdb.commons.client.sync.SyncDataNodeInternalServiceClient;
import org.apache.iotdb.commons.consensus.ConsensusGroupId;
import org.apache.iotdb.consensus.common.response.ConsensusWriteResponse;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.consensus.ConsensusImpl;
import org.apache.iotdb.db.exception.mpp.FragmentInstanceDispatchException;
import org.apache.iotdb.db.exception.sql.SemanticException;
import org.apache.iotdb.db.mpp.execution.fragment.FragmentInstanceInfo;
import org.apache.iotdb.db.mpp.plan.analyze.QueryType;
import org.apache.iotdb.db.mpp.plan.analyze.SchemaValidator;
import org.apache.iotdb.db.mpp.plan.planner.plan.FragmentInstance;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertNode;
import org.apache.iotdb.mpp.rpc.thrift.TFragmentInstance;
import org.apache.iotdb.mpp.rpc.thrift.TSendFragmentInstanceReq;
import org.apache.iotdb.mpp.rpc.thrift.TSendFragmentInstanceResp;
import org.apache.iotdb.rpc.TSStatusCode;

import com.google.common.util.concurrent.SettableFuture;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class FragmentInstanceDispatcherImpl implements IFragInstanceDispatcher {

  private static final Logger logger =
      LoggerFactory.getLogger(FragmentInstanceDispatcherImpl.class);
  private final ExecutorService executor;
  private final ExecutorService writeOperationExecutor;
  private final QueryType type;
  private final String localhostIpAddr;
  private final int localhostInternalPort;
  private final IClientManager<TEndPoint, SyncDataNodeInternalServiceClient>
      internalServiceClientManager;

  public FragmentInstanceDispatcherImpl(
      QueryType type,
      ExecutorService executor,
      ExecutorService writeOperationExecutor,
      IClientManager<TEndPoint, SyncDataNodeInternalServiceClient> internalServiceClientManager) {
    this.type = type;
    this.executor = executor;
    this.writeOperationExecutor = writeOperationExecutor;
    this.internalServiceClientManager = internalServiceClientManager;
    this.localhostIpAddr = IoTDBDescriptor.getInstance().getConfig().getInternalIp();
    this.localhostInternalPort = IoTDBDescriptor.getInstance().getConfig().getInternalPort();
  }

  @Override
  public Future<FragInstanceDispatchResult> dispatch(List<FragmentInstance> instances) {
    if (type == QueryType.READ) {
      return dispatchRead(instances);
    } else {
      return dispatchWrite(instances);
    }
  }

  // TODO: (xingtanzjr) currently we use a sequential dispatch policy for READ, which is
  //  unsafe for current FragmentInstance scheduler framework. We need to implement the
  //  topological dispatch according to dependency relations between FragmentInstances
  private Future<FragInstanceDispatchResult> dispatchRead(List<FragmentInstance> instances) {
    return executor.submit(
        () -> {
          for (FragmentInstance instance : instances) {
            boolean accepted = dispatchOneInstance(instance);
            if (!accepted) {
              return new FragInstanceDispatchResult(false);
            }
          }
          return new FragInstanceDispatchResult(true);
        });
  }

  // TODO: (xingtanzjr) Return the detailed write states for each FragmentInstance
  private Future<FragInstanceDispatchResult> dispatchWrite(List<FragmentInstance> instances) {
    List<Future<Boolean>> futures = new LinkedList<>();
    for (FragmentInstance instance : instances) {
      futures.add(writeOperationExecutor.submit(() -> dispatchOneInstance(instance)));
    }
    SettableFuture<FragInstanceDispatchResult> resultFuture = SettableFuture.create();
    for (Future<Boolean> future : futures) {
      try {
        Boolean success = future.get();
        if (!success) {
          resultFuture.set(new FragInstanceDispatchResult(false));
          break;
        }
      } catch (ExecutionException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        resultFuture.setException(e);
        break;
      }
    }
    resultFuture.set(new FragInstanceDispatchResult(true));
    return resultFuture;
  }

  private boolean dispatchOneInstance(FragmentInstance instance)
      throws FragmentInstanceDispatchException {
    TEndPoint endPoint = instance.getHostDataNode().getInternalEndPoint();
    if (isDispatchedToLocal(endPoint)) {
      return dispatchLocally(instance);
    } else {
      return dispatchRemote(instance, endPoint);
    }
  }

  private boolean isDispatchedToLocal(TEndPoint endPoint) {
    return this.localhostIpAddr.equals(endPoint.getIp()) && localhostInternalPort == endPoint.port;
  }

  private boolean dispatchRemote(FragmentInstance instance, TEndPoint endPoint)
      throws FragmentInstanceDispatchException {
    try (SyncDataNodeInternalServiceClient client =
        internalServiceClientManager.borrowClient(endPoint)) {
      ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
      instance.serializeRequest(buffer);
      buffer.flip();
      TConsensusGroupId groupId = instance.getRegionReplicaSet().getRegionId();
      TSendFragmentInstanceReq req =
          new TSendFragmentInstanceReq(
              new TFragmentInstance(buffer), groupId, instance.getType().toString());
      TSendFragmentInstanceResp resp = client.sendFragmentInstance(req);
      return resp.accepted;
    } catch (IOException | TException e) {
      logger.error("can't connect to node {}", endPoint, e);
      throw new FragmentInstanceDispatchException(e);
    }
  }

  private boolean dispatchLocally(FragmentInstance instance)
      throws FragmentInstanceDispatchException {
    ConsensusGroupId groupId =
        ConsensusGroupId.Factory.createFromTConsensusGroupId(
            instance.getRegionReplicaSet().getRegionId());
    switch (instance.getType()) {
      case READ:
        FragmentInstanceInfo info =
            (FragmentInstanceInfo) ConsensusImpl.getInstance().read(groupId, instance).getDataset();
        return !info.getState().isFailed();
      case WRITE:
        PlanNode planNode = instance.getFragment().getRoot();
        if (planNode instanceof InsertNode) {
          try {
            SchemaValidator.validate((InsertNode) planNode);
          } catch (SemanticException e) {
            throw new FragmentInstanceDispatchException(e);
          }
        }
        ConsensusWriteResponse resp = ConsensusImpl.getInstance().write(groupId, instance);
        return TSStatusCode.SUCCESS_STATUS.getStatusCode() == resp.getStatus().getCode();
    }
    throw new UnsupportedOperationException(
        String.format("unknown query type [%s]", instance.getType()));
  }

  @Override
  public void abort() {}
}
