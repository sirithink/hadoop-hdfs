/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import java.io.IOException;

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.common.HdfsConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.common.HdfsConstants.ReplicaState;

/**
 * Represents a block that is currently being constructed.<br>
 * This is usually the last block of a file opened for write or append.
 */
class BlockInfoUnderConstruction extends BlockInfo {
  /** Block state. See {@link BlockUCState} */
  private BlockUCState blockUCState;

  /**
   * Block replicas as assigned when the block was allocated.
   * This defines the pipeline order.
   */
  private ReplicaUnderConstruction[] replicas;

  /** A data-node responsible for block recovery. */
  private int primaryNodeIndex = -1;

  /** The last time the block was recovered. */
  private long lastRecoveryTime = 0;

  /**
   * ReplicaUnderConstruction contains information about replicas while
   * they are under construction.
   * The GS, the length and the state of the replica is as reported by 
   * the data-node.
   * It is not guaranteed, but expected, that data-nodes actually have
   * corresponding replicas.
   */
  static class ReplicaUnderConstruction extends Block {
    private DatanodeDescriptor expectedLocation;
    private ReplicaState state;

    ReplicaUnderConstruction(Block block,
                             DatanodeDescriptor target,
                             ReplicaState state) {
      super(block);
      this.expectedLocation = target;
      this.state = state;
    }

    /**
     * Expected block replica location as assigned when the block was allocated.
     * This defines the pipeline order.
     * It is not guaranteed, but expected, that the data-node actually has
     * the replica.
     */
    DatanodeDescriptor getExpectedLocation() {
      return expectedLocation;
    }

    /**
     * Get replica state as reported by the data-node.
     */
    ReplicaState getState() {
      return state;
    }

    /**
     * Is data-node the replica belongs to alive.
     */
    boolean isAlive() {
      return expectedLocation.isAlive;
    }

    @Override // Block
    public int hashCode() {
      return super.hashCode();
    }

    @Override // Block
    public boolean equals(Object obj) {
      // Sufficient to rely on super's implementation
      return (this == obj) || super.equals(obj);
    }
  }

  /**
   * Create block and set its state to
   * {@link BlockUCState#UNDER_CONSTRUCTION}.
   */
  BlockInfoUnderConstruction(Block blk, int replication) {
    this(blk, replication, BlockUCState.UNDER_CONSTRUCTION, null);
  }

  BlockInfoUnderConstruction(Block blk, int replication,
                             BlockUCState state,
                             DatanodeDescriptor[] targets) {
    super(blk, replication);
    assert getBlockUCState() != BlockUCState.COMPLETE :
      "BlockInfoUnderConstruction cannot be in COMPLETE state";
    this.blockUCState = state;
    setLocations(targets);
  }

  /**
   * Convert an under construction block to a complete block.
   * 
   * @return BlockInfo - a complete block.
   * @throws IOException if the state of the block 
   * (the generation stamp and the length) has not been committed by 
   * the client or it does not have at least a minimal number of replicas 
   * reported from data-nodes. 
   */
  BlockInfo convertToCompleteBlock() throws IOException {
    assert getBlockUCState() != BlockUCState.COMPLETE :
      "Trying to convert a COMPLETE block";
    if(getBlockUCState() != BlockUCState.COMMITTED)
      throw new IOException(
          "Cannot complete block: block has not been COMMITTED by the client");
    return new BlockInfo(this);
  }

  void setLocations(DatanodeDescriptor[] targets) {
    int numLocations = targets == null ? 0 : targets.length;
    this.replicas = new ReplicaUnderConstruction[numLocations];
    for(int i = 0; i < numLocations; i++)
      replicas[i] =
        new ReplicaUnderConstruction(this, targets[i], ReplicaState.RBW);
  }

  /**
   * Create array of expected replica locations
   * (as has been assigned by chooseTargets()).
   */
  private DatanodeDescriptor[] getExpectedLocations() {
    int numLocations = replicas == null ? 0 : replicas.length;
    DatanodeDescriptor[] locations = new DatanodeDescriptor[numLocations];
    for(int i = 0; i < numLocations; i++)
      locations[i] = replicas[i].getExpectedLocation();
    return locations;
  }

  int getNumLocations() {
    return replicas == null ? 0 : replicas.length;
  }

  /**
   * Return the state of the block under construction.
   * @see BlockUCState
   */
  @Override // BlockInfo
  BlockUCState getBlockUCState() {
    return blockUCState;
  }

  void setBlockUCState(BlockUCState s) {
    blockUCState = s;
  }

  /**
   * Commit block's length and generation stamp as reported by the client.
   * Set block state to {@link BlockUCState#COMMITTED}.
   * @param block - contains client reported block length and generation 
   * @throws IOException if block ids are inconsistent.
   */
  void commitBlock(Block block) throws IOException {
    if(getBlockId() != block.getBlockId())
      throw new IOException("Trying to commit inconsistent block: id = "
          + block.getBlockId() + ", expected id = " + getBlockId());
    blockUCState = BlockUCState.COMMITTED;
    this.set(getBlockId(), block.getNumBytes(), block.getGenerationStamp());
  }

  /**
   * Initialize lease recovery for this block.
   * Find the first alive data-node starting from the previous primary.
   */
  void assignPrimaryDatanode() {
    if (replicas.length == 0) {
      NameNode.stateChangeLog.warn("BLOCK*"
        + " INodeFileUnderConstruction.initLeaseRecovery:"
        + " No blocks found, lease removed.");
    }

    int previous = primaryNodeIndex;
    for(int i = 1; i <= replicas.length; i++) {
      int j = (previous + i)%replicas.length;
      if (replicas[j].isAlive()) {
        primaryNodeIndex = j;
        DatanodeDescriptor primary = replicas[j].getExpectedLocation(); 
        primary.addBlockToBeRecovered(this, getExpectedLocations());
        NameNode.stateChangeLog.info("BLOCK* " + this
          + " recovery started, primary=" + primary);
        return;
      }
    }
  }

  /**
   * Update lastRecoveryTime if expired.
   * @return true if lastRecoveryTimeis updated. 
   */
  boolean setLastRecoveryTime(long now) {
    boolean expired = now - lastRecoveryTime > NameNode.LEASE_RECOVER_PERIOD;
    if (expired) {
      lastRecoveryTime = now;
    }
    return expired;
  }

  @Override // BlockInfo
  // BlockInfoUnderConstruction participates in maps the same way as BlockInfo
  public int hashCode() {
    return super.hashCode();
  }

  @Override // BlockInfo
  public boolean equals(Object obj) {
    // Sufficient to rely on super's implementation
    return (this == obj) || super.equals(obj);
  }
}