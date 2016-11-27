/*******************************************************************************
 *
 *    Copyright (C) 2015-2016
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
package de.fernunihagen.dna.scalephant.performance.osm;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;

import crosby.binary.osmosis.OsmosisReader;
import de.fernunihagen.dna.scalephant.performance.osm.filter.OSMBuildingsEntityFilter;
import de.fernunihagen.dna.scalephant.performance.osm.filter.OSMMultiPointEntityFilter;
import de.fernunihagen.dna.scalephant.performance.osm.filter.OSMRoadsEntityFilter;
import de.fernunihagen.dna.scalephant.performance.osm.filter.OSMSinglePointEntityFilter;
import de.fernunihagen.dna.scalephant.performance.osm.filter.OSMTrafficSignalEntityFilter;
import de.fernunihagen.dna.scalephant.performance.osm.filter.OSMTreeEntityFilter;
import de.fernunihagen.dna.scalephant.performance.osm.util.GeometricalStructure;
import de.fernunihagen.dna.scalephant.performance.osm.util.SerializableNode;
import de.fernunihagen.dna.scalephant.performance.osm.util.SerializerHelper;

public class OSMFileReader implements Runnable {

	/**
	 * The single point filter
	 */
	protected final static Map<String, OSMSinglePointEntityFilter> singlePointFilter = new HashMap<String, OSMSinglePointEntityFilter>();
	
	/**
	 * The multi point filter
	 */
	protected final static Map<String, OSMMultiPointEntityFilter> multiPointFilter = new HashMap<String, OSMMultiPointEntityFilter>();
	
	/**
	 * The filename to parse
	 */
	protected final String filename;
	
	/**
	 * The type to import
	 */
	protected final String type;
	
	/**
	 * The callback for completed objects
	 */
	protected final OSMStructureCallback structureCallback;
	
	static {
		singlePointFilter.put("tree", new OSMTreeEntityFilter());
		singlePointFilter.put("trafficsignals", new OSMTrafficSignalEntityFilter());
		
		multiPointFilter.put("roads", new OSMRoadsEntityFilter());
		multiPointFilter.put("buildings", new OSMBuildingsEntityFilter());
	}
	
	public OSMFileReader(final String filename, final String type, final OSMStructureCallback structureCallback) {
		super();
		this.filename = filename;
		this.type = type;
		this.structureCallback = structureCallback;
	}

	protected class OSMSinglePointSink implements Sink {

		/**
		 * The entity filter
		 */
		private final OSMSinglePointEntityFilter entityFilter;

		protected OSMSinglePointSink(final OSMSinglePointEntityFilter entityFilter) {
			this.entityFilter = entityFilter;
		}

		@Override
		public void release() {
		}

		@Override
		public void complete() {
		}

		@Override
		public void initialize(final Map<String, Object> arg0) {
		}

		@Override
		public void process(final EntityContainer entityContainer) {
			
			if(entityContainer.getEntity() instanceof Node) {
				final Node node = (Node) entityContainer.getEntity();						
				
				if(entityFilter.forwardNode(node)) {
					final GeometricalStructure geometricalStructure = new GeometricalStructure(node.getId());
					geometricalStructure.addPoint(node.getLatitude(), node.getLongitude());
					structureCallback.processStructure(geometricalStructure);
				}
			}
		}
	}

	protected class OSMMultipointSink implements Sink {
		
		/**
		 * The db instance
		 */
		protected final DB db;
		
		/**
		 * The node map
		 */
		protected final Map<Long, byte[]> nodeMap;
		
		/**
		 * The entity filter
		 */
		protected final OSMMultiPointEntityFilter entityFilter;
		
		/**
		 * The node serializer
		 */
		protected SerializerHelper<SerializableNode> serializerHelper = new SerializerHelper<>();

		protected OSMMultipointSink(final OSMMultiPointEntityFilter entityFilter) {
			this.entityFilter = entityFilter;
	    	
			try {
				final File dbFile = File.createTempFile("osm-db", ".tmp");
				dbFile.delete();
				
				// Use a disk backed map, to process files > Memory
				this.db = DBMaker.fileDB(dbFile).fileMmapEnableIfSupported().fileDeleteAfterClose().make();
				this.nodeMap = db.hashMap("osm-id-map").keySerializer(Serializer.LONG)
				        .valueSerializer(Serializer.BYTE_ARRAY)
				        .create();
				
			} catch (IOException e) {
				throw new IllegalArgumentException(e);
			}
			
		}

		@Override
		public void initialize(Map<String, Object> arg0) {
			
		}

		@Override
		public void complete() {
			
		}

		@Override
		public void release() {
			
		}

		@Override
		public void process(final EntityContainer entityContainer) {
			try {
				if(entityContainer.getEntity() instanceof Node) {
					final Node node = (Node) entityContainer.getEntity();
					final SerializableNode serializableNode = new SerializableNode(node);
					nodeMap.put(node.getId(), serializerHelper.toByteArray(serializableNode));
				} else if(entityContainer.getEntity() instanceof Way) {
					final Way way = (Way) entityContainer.getEntity();
					final boolean forward = entityFilter.forwardNode(way.getTags());

					if(forward) {
						insertWay(way, nodeMap);	
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		/**
		 * Handle the given way
		 * @param way
		 * @param nodeMap 
		 */
		protected void insertWay(final Way way, final Map<Long, byte[]> nodeMap) {
			final GeometricalStructure geometricalStructure = new GeometricalStructure(way.getId());
			
			try {
				for(final WayNode wayNode : way.getWayNodes()) {
					
					if(! nodeMap.containsKey(wayNode.getNodeId())) {
						System.err.println("Unable to find node for way: " + wayNode.getNodeId());
						return;
					}
					
					final byte[] nodeBytes = nodeMap.get(wayNode.getNodeId());
					final SerializableNode serializableNode = serializerHelper.loadFromByteArray(nodeBytes);
					geometricalStructure.addPoint(serializableNode.getLatitude(), serializableNode.getLongitude());
				}
				
				if(geometricalStructure.getNumberOfPoints() > 0) {
						structureCallback.processStructure(geometricalStructure);				
				}
			} catch (ClassNotFoundException | IOException e) {
				e.printStackTrace();
			}
			
		}
	}
	
	/**
	 * Get the names of the available filter
	 * @return
	 */
	public static String getFilterNames() {
		return getAllFilter().stream().collect(Collectors.joining("|"));
	}

	/**
	 * Get all known filter
	 * @return
	 */
	public static Set<String> getAllFilter() {
		final Set<String> names = new HashSet<>();
		names.addAll(singlePointFilter.keySet());
		names.addAll(multiPointFilter.keySet());
		return Collections.unmodifiableSet(names);
	}


	/**
	 * Run the importer
	 * @throws ExecutionException 
	 */
	@Override
	public void run() {
		try {
			final OsmosisReader reader = new OsmosisReader(new FileInputStream(filename));
			
			if(singlePointFilter.containsKey(type)) {
				final OSMSinglePointEntityFilter entityFilter = singlePointFilter.get(type);
				reader.setSink(new OSMSinglePointSink(entityFilter));
			}
			
			if(multiPointFilter.containsKey(type)) {
				final OSMMultiPointEntityFilter entityFilter = multiPointFilter.get(type);			
				reader.setSink(new OSMMultipointSink(entityFilter));
			}
			
			reader.run();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}
	
}
