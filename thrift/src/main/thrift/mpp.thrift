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
include "common.thrift"
namespace java org.apache.iotdb.mpp.rpc.thrift

struct TCreateSchemaRegionReq {
    1: required common.TRegionReplicaSet regionReplicaSet
    2: required string storageGroup
}

struct TCreateDataRegionReq {
    1: required common.TRegionReplicaSet regionReplicaSet
    2: required string storageGroup
    3: optional i64 ttl
}

struct TInvalidateCacheReq {
    1: required bool storageGroup
    2: required string fullPath
}

struct TMigrateSchemaRegionReq{
    1: required i32 sourceDataNodeID
    2: required i32 targetDataNodeID
    3: required i32 schemaRegionID
}

struct TMigrateDataRegionReq{
    1: required i32 sourceDataNodeID
    2: required i32 targetDataNodeID
    3: required i32 dataRegionID
}

struct TFragmentInstanceId {
  1: required string queryId
  2: required i32 fragmentId
  3: required string instanceId
}

struct TGetDataBlockRequest {
  1: required TFragmentInstanceId sourceFragmentInstanceId
  2: required i32 startSequenceId
  3: required i32 endSequenceId
}

struct TGetDataBlockResponse {
  1: required list<binary> tsBlocks
}

struct TAcknowledgeDataBlockEvent {
  1: required TFragmentInstanceId sourceFragmentInstanceId
  2: required i32 startSequenceId
  3: required i32 endSequenceId
}

struct TNewDataBlockEvent {
  1: required TFragmentInstanceId targetFragmentInstanceId
  2: required string targetPlanNodeId
  3: required TFragmentInstanceId sourceFragmentInstanceId
  4: required i32 startSequenceId
  5: required list<i64> blockSizes
}

struct TEndOfDataBlockEvent {
  1: required TFragmentInstanceId targetFragmentInstanceId
  2: required string targetPlanNodeId
  3: required TFragmentInstanceId sourceFragmentInstanceId
  4: required i32 lastSequenceId
}

struct TFragmentInstance {
  1: required binary body
}

struct TSendFragmentInstanceReq {
  1: required TFragmentInstance fragmentInstance
  2: required common.TConsensusGroupId consensusGroupId
  3: required string queryType
}

struct TSendFragmentInstanceResp {
  1: required bool accepted
  2: optional string message
}

struct TFetchFragmentInstanceStateReq {
  1: required TFragmentInstanceId fragmentInstanceId
}

// TODO: need to supply more fields according to implementation
struct TFragmentInstanceStateResp {
  1: required string state
}

struct TCancelQueryReq {
  1: required string queryId
  2: required list<TFragmentInstanceId> fragmentInstanceIds
}

struct TCancelPlanFragmentReq {
  1: required string planFragmentId
}

struct TCancelFragmentInstanceReq {
  1: required TFragmentInstanceId fragmentInstanceId
}

struct TCancelResp {
  1: required bool cancelled
  2: optional string messsage
}

struct TSchemaFetchRequest {
  1: required binary serializedPathPatternTree
  2: required bool isPrefixMatchPath
}

struct TSchemaFetchResponse {
  1: required binary serializedSchemaTree
}

service InternalService {

  // -----------------------------------For Data Node-----------------------------------------------

  TSendFragmentInstanceResp sendFragmentInstance(TSendFragmentInstanceReq req);

  TFragmentInstanceStateResp fetchFragmentInstanceState(TFetchFragmentInstanceStateReq req);

  TCancelResp cancelQuery(TCancelQueryReq req);

  TCancelResp cancelPlanFragment(TCancelPlanFragmentReq req);

  TCancelResp cancelFragmentInstance(TCancelFragmentInstanceReq req);

  TSchemaFetchResponse fetchSchema(TSchemaFetchRequest req)


  // -----------------------------------For Config Node-----------------------------------------------

  /**
   * Config node will create a schema region on a list of data nodes.
   *
   * @param data nodes of the schema region, and schema region id generated by config node
   */
  common.TSStatus createSchemaRegion(TCreateSchemaRegionReq req)

  /**
   * Config node will create a data region on a list of data nodes.
   *
   * @param data nodes of the data region, and data region id generated by config node
   */
  common.TSStatus createDataRegion(TCreateDataRegionReq req)

  /**
     * Config node will invalidate Partition Info cache.
     *
     * @param bool:isStorageGroup, string:fullPath
     */
  common.TSStatus invalidatePartitionCache(TInvalidateCacheReq req)

  /**
       * Config node will invalidate Schema Info cache.
       *
       * @param bool:isStorageGroup, string:fullPath
       */
    common.TSStatus invalidateSchemaCache(TInvalidateCacheReq req)

  /**
     * Config node will delete a data/schema region of a certain storageGroup.
     *
     * @param data nodes of the data region, and data region id generated by config node
     */
  common.TSStatus deleteRegion(common.TConsensusGroupId consensusGroupId)


  /**
   * Config node will migrate a schema region from one data node to another
   *
   * @param previous data node in the schema region, new data node, and schema region id
   */
  common.TSStatus migrateSchemaRegion(TMigrateSchemaRegionReq req)

  /**
   * Config node will migrate a data region from one data node to another
   *
   * @param previous data node in the data region, new data node, and dataregion id
   */
  common.TSStatus migrateDataRegion(TMigrateDataRegionReq req)

  /**
  * ConfigNode will ask DataNode for heartbeat in every few seconds.
  *
  * @param ConfigNode will send the latest config_node_list and load balancing policies in THeartbeatReq
  **/
  common.THeartbeatResp getHeartBeat(common.THeartbeatReq req)
}

service DataBlockService {
  TGetDataBlockResponse getDataBlock(TGetDataBlockRequest req);

  void onAcknowledgeDataBlockEvent(TAcknowledgeDataBlockEvent e);

  void onNewDataBlockEvent(TNewDataBlockEvent e);

  void onEndOfDataBlockEvent(TEndOfDataBlockEvent e);
}
