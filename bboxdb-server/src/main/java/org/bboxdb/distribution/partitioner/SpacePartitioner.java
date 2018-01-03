/*******************************************************************************
 *
 *    Copyright (C) 2015-2018 the BBoxDB project
 *  
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License. 
 *    
 *******************************************************************************/
package org.bboxdb.distribution.partitioner;

import org.bboxdb.distribution.DistributionGroupName;
import org.bboxdb.distribution.DistributionRegion;
import org.bboxdb.distribution.placement.ResourceAllocationException;
import org.bboxdb.distribution.zookeeper.ZookeeperClient;
import org.bboxdb.distribution.zookeeper.ZookeeperException;
import org.bboxdb.distribution.zookeeper.ZookeeperNotFoundException;

public interface SpacePartitioner {
	
	/**
	 * All dependencies are set, init the partitioner
	 * @param spacePartitionerConfig 
	 * @throws ZookeeperException
	 */
	public void init(final String spacePartitionerConfig, 
			final DistributionGroupName distributionGroupName, 
			final ZookeeperClient zookeeperClient, 
			final DistributionGroupZookeeperAdapter distributionGroupAdapter) throws ZookeeperException;

	/**
	 * Get the root node
	 * @return
	 */
	public DistributionRegion getRootNode();
	
	/**
	 * Allocate systems to a new region
	 * @param region
	 * @throws ZookeeperException
	 * @throws ResourceAllocationException
	 * @throws ZookeeperNotFoundException
	 */
	public void allocateSystemsToRegion(final DistributionRegion region) 
			throws ZookeeperException, ResourceAllocationException, ZookeeperNotFoundException;

	/**
	 * Split the node on the given position
	 * @param regionToSplit
	 * @param splitPosition
	 * @throws ZookeeperException
	 * @throws ResourceAllocationException
	 * @throws ZookeeperNotFoundException
	 */
	public void splitNode(final DistributionRegion regionToSplit, final double splitPosition) 
			throws ZookeeperException, ResourceAllocationException, ZookeeperNotFoundException;
	
	/**
	 * Register a changed callback
	 * @param callback
	 * @return
	 */
	public boolean registerCallback(final DistributionRegionChangedCallback callback);
	
	/**
	 * Remove a changed callback
	 * @param callback
	 * @return
	 */
	public boolean unregisterCallback(final DistributionRegionChangedCallback callback);
}
