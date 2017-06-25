/*******************************************************************************
 *
 *    Copyright (C) 2015-2017 the BBoxDB project
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
package org.bboxdb.storage.sstable.spatialindex.rtree;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.bboxdb.storage.StorageManagerException;
import org.bboxdb.storage.entity.BoundingBox;
import org.bboxdb.storage.sstable.SSTableConst;
import org.bboxdb.storage.sstable.spatialindex.SpatialIndexBuilder;
import org.bboxdb.storage.sstable.spatialindex.SpatialIndexEntry;
import org.bboxdb.util.io.DataEncoderHelper;

public class RTreeSpatialIndexBuilder implements SpatialIndexBuilder {

	/**
	 * The node factory
	 */
	protected final RTreeNodeFactory nodeFactory;
	
	/**
	 * The root node of the tree
	 */
	protected RTreeDirectoryNode rootNode;
	
	/**
	 * The max size of a child node
	 */
	protected int maxNodeSize;
	
	/**
	 * The default max node size
	 */
	public final static int DEFAULT_NODE_SIZE = 64;
	
	/**
	 * The byte for a non existing child node
	 */
	public final static byte MAGIC_CHILD_NODE_NOT_EXISTING = 0;
	
	/**
	 * The byte for a following child node
	 */
	public final static byte MAGIC_CHILD_NODE_FOLLOWING = 1;
	

	public RTreeSpatialIndexBuilder() {
		this(DEFAULT_NODE_SIZE);
	}

	public RTreeSpatialIndexBuilder(final int maxNodeSize) {
		
		if(maxNodeSize <= 0) {
			throw new IllegalArgumentException("Unable to construct an index with max node size: " 
					+ maxNodeSize);
		}
		
		this.maxNodeSize = maxNodeSize;
		this.nodeFactory = new RTreeNodeFactory();
		this.rootNode = nodeFactory.buildDirectoryNode();
	}

	@Override
	public void writeToStream(final OutputStream outputStream) throws StorageManagerException {
		
		final RTreeDirectoryNode NULL_RTREE_ELEMENT = new RTreeDirectoryNode(-1);
		
		try {
			// Write the magic bytes
			outputStream.write(SSTableConst.MAGIC_BYTES_SPATIAL_RTREE_INDEX);
			
			// Write the tree configuration
			final ByteBuffer nodeSizeBytes = DataEncoderHelper.intToByteBuffer(maxNodeSize);
			outputStream.write(nodeSizeBytes.array());

			final Deque<RTreeDirectoryNode> nodesQueue = new ArrayDeque<>();
			nodesQueue.push(rootNode);

			while(! nodesQueue.isEmpty()) {
				final RTreeDirectoryNode node = nodesQueue.pop();
				// Child does not exist
				if(node == NULL_RTREE_ELEMENT) {
		    		outputStream.write(RTreeSpatialIndexBuilder.MAGIC_CHILD_NODE_NOT_EXISTING);
		    		continue;
				}
							
	    		outputStream.write(RTreeSpatialIndexBuilder.MAGIC_CHILD_NODE_FOLLOWING);
			    final ByteBuffer nodeIdBytes = DataEncoderHelper.intToByteBuffer(node.getNodeId());
			    outputStream.write(nodeIdBytes.array());
			    
			    // Write entry nodes
		    	final List<SpatialIndexEntry> indexEntries = node.getIndexEntries();
			    for(int i = 0; i < maxNodeSize; i++) {
					if(i < indexEntries.size()) {
			    		outputStream.write(RTreeSpatialIndexBuilder.MAGIC_CHILD_NODE_FOLLOWING);
			    		indexEntries.get(i).writeToStream(outputStream);
			    	} else {
			    		outputStream.write(RTreeSpatialIndexBuilder.MAGIC_CHILD_NODE_NOT_EXISTING);
			    	}
			    }
			    
			    // Write directory nodes
		    	final List<RTreeDirectoryNode> directoryNodeChilds = node.getDirectoryNodeChilds();
			    for(int i = maxNodeSize - 1; i >= 0; i--) {
					if(i < directoryNodeChilds.size()) {
			    		nodesQueue.addFirst(directoryNodeChilds.get(i));
			    	} else {
			    		nodesQueue.addFirst(NULL_RTREE_ELEMENT);
			    	}
			    }			    
			}
		} catch (IOException e) {
			throw new StorageManagerException(e);
		}
	}

	@Override
	public boolean bulkInsert(final List<SpatialIndexEntry> elements) {
		boolean result = true;
		
		for(final SpatialIndexEntry entry : elements) {
			final boolean insertResult = insert(entry);
			
			if(! insertResult) {
				result = false;
			}
		}
		
		return result;
	}
	
	/**
	 * Insert the given RTreeSpatialIndexEntry into the tree
	 * @param entry
	 * @return 
	 */
	@Override
	public boolean insert(final SpatialIndexEntry entry) {

		if(entry.getBoundingBox() == null || entry.getBoundingBox() == BoundingBox.EMPTY_BOX) {
			return false;
		}
		
		final RTreeDirectoryNode childNode = insert(rootNode, entry);
		adjustTree(childNode);	
		
		return true;
	}
	
	/**
	 * Insert the given RTreeSpatialIndexEntry into the tree into the base node or below
	 * @param insertBaseNode
	 * @param entry
	 * @return
	 */
	protected RTreeDirectoryNode insert(final RTreeDirectoryNode insertBaseNode, final SpatialIndexEntry entry) {

		final BoundingBox entryBox = entry.getBoundingBox();
		
		RTreeDirectoryNode childNode = insertBaseNode;

		final Deque<RTreeDirectoryNode> path = new ArrayDeque<>();
		path.push(childNode);
		
		while(! childNode.isLeafNode()) {
			if(childNode.getDirectoryNodeChilds().isEmpty()) {
				throw new RuntimeException("This is a !leaf node with no childs?");
			}
		
			childNode = childNode.findBestNodeForInsert(entryBox);
			path.push(childNode);

			if(childNode == null) {
				throw new RuntimeException("Unable to find a node for insert");
			}
		}
		
		childNode.getIndexEntries().add(entry);
		

		while(! path.isEmpty()) {
			final RTreeDirectoryNode tmpNode = path.pop();
			tmpNode.updateBoundingBox();
		}
		
		return childNode;
	}

	/**
	 * Adjust the tree, beginning from the argument to the tree root
	 * @param insertedNode
	 */
	protected void adjustTree(final RTreeDirectoryNode insertedNode) {
		
		if(insertedNode == null) {
			return;
		}
		
		RTreeDirectoryNode nodeToCheck = insertedNode;
		
		// Adjust beginning from the bottom
		do {
			//nodeToCheck.testCovering();

			if(nodeToCheck.getSize() > maxNodeSize) {
				nodeToCheck = splitNode(insertedNode);
			} else {
				nodeToCheck = nodeToCheck.getParentNode();
			}
			
		} while(nodeToCheck != null);	
	}

	/**
	 * Split the given node
	 * @param nodeToSplit
	 * @return 
	 */
	protected RTreeDirectoryNode splitNode(final RTreeDirectoryNode nodeToSplit) {
		final RTreeDirectoryNode newNode1 = nodeFactory.buildDirectoryNode();
		final RTreeDirectoryNode newNode2 = nodeFactory.buildDirectoryNode();
		RTreeDirectoryNode newParent = null;
		
		// Root node is full
		if(nodeToSplit.getParentNode() == null) {
			rootNode = nodeFactory.buildDirectoryNode();
			newParent = rootNode;
		} else {
			newParent = nodeFactory.buildDirectoryNode();
			nodeToSplit.getParentNode().addDirectoryNodeChild(newParent);
			nodeToSplit.getParentNode().removeDirectoryNodeChild(nodeToSplit);
		}
		
		// Insert new directory node
		newParent.addDirectoryNodeChild(newNode1);
		newParent.addDirectoryNodeChild(newNode2);
		newNode1.setParentNode(newParent);
		newNode2.setParentNode(newParent);
		
		// Find seeds and distribute data
		if(nodeToSplit.isLeafNode()) {
			distributeLeafData(nodeToSplit, newNode1, newNode2);
		} else {
			distributeIndexData(nodeToSplit, newNode1, newNode2);
		}

		// Recalculate the bounding boxes
		newNode1.updateBoundingBox();
		newNode2.updateBoundingBox();
		newParent.updateBoundingBox();
		
		return newParent;
	}
	
	@Override
	public List<? extends SpatialIndexEntry> getEntriesForRegion(final BoundingBox boundingBox) {
		return rootNode.getEntriesForRegion(boundingBox);
	}

	/**
	 * Distribute the leaf data
	 * @param nodeToSplit
	 * @param newNode1
	 * @param newNode2
	 */
	protected void distributeIndexData(final RTreeDirectoryNode nodeToSplit, final RTreeDirectoryNode newNode1,
			final RTreeDirectoryNode newNode2) {
		
		final List<RTreeDirectoryNode> dataToDistribute = nodeToSplit.getDirectoryNodeChilds();
		final List<RTreeDirectoryNode> seeds = new ArrayList<>();
		
		final QuadraticSeedPicker<RTreeDirectoryNode> seedPicker = new QuadraticSeedPicker<>();
		seedPicker.quadraticPickSeeds(dataToDistribute, seeds);
		
		newNode1.addDirectoryNodeChild(seeds.get(0));
		newNode2.addDirectoryNodeChild(seeds.get(1));
		
		for(int i = 0; i < dataToDistribute.size(); i++) {
			newNode1.updateBoundingBox();
			newNode2.updateBoundingBox();

			final int remainingObjects = dataToDistribute.size() - i;
			final RTreeDirectoryNode entry = dataToDistribute.get(i);
			
			if(newNode1.getDirectoryNodeChilds().size() + remainingObjects <= maxNodeSize / 2) {
				newNode1.addDirectoryNodeChild(entry);
				continue;
			}
			
			if(newNode2.getDirectoryNodeChilds().size() + remainingObjects <= maxNodeSize / 2) {
				newNode2.addDirectoryNodeChild(entry);
				continue;
			}
			
			final double node1Enlargement = newNode1.getBoundingBox().calculateEnlargement(entry.getBoundingBox());
			final double node2Enlargement = newNode2.getBoundingBox().calculateEnlargement(entry.getBoundingBox());
		
			if(node1Enlargement == node2Enlargement) {
				if(newNode1.getDirectoryNodeChilds().size() < newNode2.getDirectoryNodeChilds().size()) {
					newNode1.addDirectoryNodeChild(entry);
					continue;
				} else {
					newNode2.addDirectoryNodeChild(entry);
					continue;
				}
			}
			
			if(node1Enlargement < node2Enlargement) {
				newNode1.addDirectoryNodeChild(entry);
				continue;
			} else {
				newNode2.addDirectoryNodeChild(entry);
				continue;
			}
		}
	}

	/**
	 * Distribute the index data
	 * @param nodeToSplit
	 * @param newNode1
	 * @param newNode2
	 */
	protected void distributeLeafData(final RTreeDirectoryNode nodeToSplit, 
			final RTreeDirectoryNode newNode1,
			final RTreeDirectoryNode newNode2) {
		
		final List<SpatialIndexEntry> dataToDistribute = nodeToSplit.getIndexEntries();
		final List<SpatialIndexEntry> seeds = new ArrayList<>();
		
		final QuadraticSeedPicker<SpatialIndexEntry> seedPicker = new QuadraticSeedPicker<>();
		seedPicker.quadraticPickSeeds(dataToDistribute, seeds);

		insert(newNode1, seeds.get(0));
		insert(newNode2, seeds.get(1));
		
		for(int i = 0; i < dataToDistribute.size(); i++) {
			newNode1.updateBoundingBox();
			newNode2.updateBoundingBox();

			final int remainingObjects = dataToDistribute.size() - i;
			final SpatialIndexEntry entry = dataToDistribute.get(i);
			
			if(newNode1.getIndexEntries().size() + remainingObjects <= maxNodeSize / 2) {
				insert(newNode1, entry);
				continue;
			}
			
			if(newNode2.getIndexEntries().size() + remainingObjects <= maxNodeSize / 2) {
				insert(newNode2, entry);
				continue;
			}
			
			final double node1Enlargement = newNode1.getBoundingBox().calculateEnlargement(entry.getBoundingBox());
			final double node2Enlargement = newNode2.getBoundingBox().calculateEnlargement(entry.getBoundingBox());
		
			if(node1Enlargement == node2Enlargement) {
				if(newNode1.getIndexEntries().size() < newNode2.getIndexEntries().size()) {
					insert(newNode1, entry);
					continue;
				} else {
					insert(newNode2, entry);
					continue;
				}
			}
			
			if(node1Enlargement < node2Enlargement) {
				insert(newNode1, entry);
				continue;
			} else {
				insert(newNode2, entry);
				continue;
			}
		}
	}
	
	/**
	 * Get the maximal node size
	 * @return
	 */
	public int getMaxNodeSize() {
		return maxNodeSize;
	}
}