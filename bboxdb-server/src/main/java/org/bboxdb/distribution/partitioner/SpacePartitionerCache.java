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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.bboxdb.distribution.zookeeper.ZookeeperClient;
import org.bboxdb.distribution.zookeeper.ZookeeperClientFactory;
import org.bboxdb.distribution.zookeeper.ZookeeperException;
import org.bboxdb.distribution.zookeeper.ZookeeperNotFoundException;
import org.bboxdb.network.client.BBoxDBException;
import org.bboxdb.storage.entity.TupleStoreName;

public class SpacePartitionerCache {
	
	/**
	 * Mapping between the string group and the group object
	 */
	protected final static Map<String, SpacePartitioner> groupGroupMap;

	static {
		groupGroupMap = Collections.synchronizedMap(new HashMap<String, SpacePartitioner>());
	}
	
	/**
	 * Get the distribution region for the given group name
	 * @param groupName
	 * @return
	 * @throws ZookeeperException 
	 * @throws ZookeeperNotFoundException 
	 */
	public static SpacePartitioner getSpacePartitionerForGroupName(final String groupName) 
			throws ZookeeperException {
		
		// We can not synchronize this method, the space partitioner needs to lock
		// the DistributionRegionIdMapperManager which can also call this class. This leads
		// to a deadlock, see commit 202159566873af26b94979db5fc0691f10f567d5
		
		final ZookeeperClient zookeeperClient = ZookeeperClientFactory.getZookeeperClient();
		
		if(! groupGroupMap.containsKey(groupName)) {
			final DistributionGroupZookeeperAdapter distributionGroupZookeeperAdapter 
				= new DistributionGroupZookeeperAdapter(zookeeperClient);
			
			final SpacePartitioner adapter = distributionGroupZookeeperAdapter.getSpaceparitioner(groupName);
			groupGroupMap.put(groupName, adapter);
		}
		
		return groupGroupMap.get(groupName);
	}
	
	/**
	 * Get the distribution region for the given table name
	 * @param groupName
	 * @return
	 * @throws ZookeeperException 
	 * @throws BBoxDBException 
	 * @throws ZookeeperNotFoundException 
	 */
	public static SpacePartitioner getSpaceParitionerForTableName(
			final TupleStoreName ssTableName) throws ZookeeperException, BBoxDBException {
		
		if(! ssTableName.isValid()) {
			throw new BBoxDBException("Invalid tablename: " + ssTableName);
		}

		return getSpacePartitionerForGroupName(ssTableName.getDistributionGroup());
	}
}
