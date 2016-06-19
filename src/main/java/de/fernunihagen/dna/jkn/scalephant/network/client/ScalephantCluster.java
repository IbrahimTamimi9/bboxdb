package de.fernunihagen.dna.jkn.scalephant.network.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fernunihagen.dna.jkn.scalephant.distribution.DistributionGroupCache;
import de.fernunihagen.dna.jkn.scalephant.distribution.DistributionRegion;
import de.fernunihagen.dna.jkn.scalephant.distribution.ZookeeperClient;
import de.fernunihagen.dna.jkn.scalephant.distribution.ZookeeperException;
import de.fernunihagen.dna.jkn.scalephant.distribution.membership.DistributedInstance;
import de.fernunihagen.dna.jkn.scalephant.distribution.membership.DistributedInstanceManager;
import de.fernunihagen.dna.jkn.scalephant.distribution.membership.event.DistributedInstanceAddEvent;
import de.fernunihagen.dna.jkn.scalephant.distribution.membership.event.DistributedInstanceDeleteEvent;
import de.fernunihagen.dna.jkn.scalephant.distribution.membership.event.DistributedInstanceEvent;
import de.fernunihagen.dna.jkn.scalephant.distribution.membership.event.DistributedInstanceEventCallback;
import de.fernunihagen.dna.jkn.scalephant.network.NetworkConnectionState;
import de.fernunihagen.dna.jkn.scalephant.storage.entity.BoundingBox;
import de.fernunihagen.dna.jkn.scalephant.storage.entity.Tuple;

public class ScalephantCluster implements Scalephant, DistributedInstanceEventCallback {
	
	/**
	 * The zookeeper connection
	 */
	protected final ZookeeperClient zookeeperClient;
	
	/**
	 * The number of in flight requests
	 * @return
	 */
	protected volatile short maxInFlightCalls = MAX_IN_FLIGHT_CALLS;
	
	/**
	 * The server connections
	 */
	protected final Map<DistributedInstance, ScalephantClient> serverConnections;
	
	/**
	 * The random generator
	 */
	protected Random randomGenerator;
	
	/**
	 * The Logger
	 */
	private final static Logger logger = LoggerFactory.getLogger(ScalephantCluster.class);

	/**
	 * Create a new instance of the ScalepahntCluster 
	 * @param zookeeperNodes
	 * @param clustername
	 */
	public ScalephantCluster(final Collection<String> zookeeperNodes, final String clustername) {
		zookeeperClient = new ZookeeperClient(zookeeperNodes, clustername);
		final HashMap<DistributedInstance, ScalephantClient> connectionMap = new HashMap<DistributedInstance, ScalephantClient>();
		serverConnections = Collections.synchronizedMap(connectionMap);
		randomGenerator = new Random();;
	}

	@Override
	public boolean connect() {
		zookeeperClient.init();
		DistributedInstanceManager.getInstance().registerListener(this);
		zookeeperClient.startMembershipObserver();
		return zookeeperClient.isConnected();
	}

	@Override
	public boolean disconnect() {
		zookeeperClient.shutdown();
		return true;
	}

	@Override
	public ClientOperationFuture deleteTable(String table) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ClientOperationFuture insertTuple(String table, Tuple tuple) {
		
		// FIXME: Demo Implementation 
		try {
			final DistributionRegion distributionRegion = DistributionGroupCache.getGroupForTableName(table, zookeeperClient);
			final Collection<String> systems = distributionRegion.getSystemsForBoundingBox(tuple.getBoundingBox());
			logger.info("Writing tuple to systems: " + systems);
			
			for(final String system : systems) {
				logger.info("Sending call to:  " + system);
				final ScalephantClient connection = serverConnections.get(system);
				connection.insertTuple(table, tuple);
			}
			
		} catch (ZookeeperException e) {
			e.printStackTrace();
		}
		
		return null;
	}

	@Override
	public ClientOperationFuture deleteTuple(String table, String key) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ClientOperationFuture listTables() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ClientOperationFuture createDistributionGroup(
			final String distributionGroup, final short replicationFactor) {

		if(serverConnections.size() == 0) {
			final ClientOperationFuture future = new ClientOperationFuture();
			future.setFailedState();
			return future;
		} else {
			final ScalephantClient scalephantClient = getRandomHost();
			return scalephantClient.createDistributionGroup(distributionGroup, replicationFactor);
		}
	}

	@Override
	public ClientOperationFuture deleteDistributionGroup(
			final String distributionGroup) {

		if(serverConnections.size() == 0) {
			final ClientOperationFuture future = new ClientOperationFuture();
			future.setFailedState();
			return future;
		} else {
			final ScalephantClient scalephantClient = getRandomHost();
			return scalephantClient.deleteDistributionGroup(distributionGroup);
		}
	}
	
	/**
	 * Get one random host from the connection list
	 * @return 
	 */
	protected ScalephantClient getRandomHost() {
		
		synchronized (serverConnections) {
			final ScalephantClient[] elements = serverConnections.values().toArray(new ScalephantClient[0]);
			final int element = randomGenerator.nextInt() % elements.length;
			return elements[element];
		}
		
	}

	@Override
	public ClientOperationFuture queryKey(String table, String key) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ClientOperationFuture queryBoundingBox(String table,
			BoundingBox boundingBox) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ClientOperationFuture queryTime(String table, long timestamp) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isConnected() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public NetworkConnectionState getConnectionState() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getInFlightCalls() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public short getMaxInFlightCalls() {
		return maxInFlightCalls;
	}

	@Override
	public void setMaxInFlightCalls(final short maxInFlightCalls) {
		this.maxInFlightCalls = maxInFlightCalls;
	}
	
	/**
	 * Add a new connection to a scalephant system
	 * @param distributedInstance
	 */
	protected synchronized void createConnection(final DistributedInstance distributedInstance) {
		logger.info("Opening connection to new node: " + distributedInstance);
		
		if(serverConnections.containsKey(distributedInstance)) {
			logger.info("We allready have a connection to: " + distributedInstance);
			return;
		}
		
		final ScalephantClient client = new ScalephantClient(distributedInstance.getInetSocketAddress());
		final boolean result = client.connect();
		
		if(! result) {
			logger.info("Unable to open connection to: " + distributedInstance);
		} else {
			logger.info("Connection successfully established: " + distributedInstance);
			serverConnections.put(distributedInstance, client);
		}
	}
	
	/**
	 * Terminate the connection to a missing scalepahnt system
	 * @param distributedInstance 
	 */
	protected synchronized void terminateConnection(final DistributedInstance distributedInstance) {
		
		logger.info("Closing connections to terminating node: " + distributedInstance);
		
		if(! serverConnections.containsKey(distributedInstance)) {
			return;
		}
		
		final ScalephantClient client = serverConnections.remove(distributedInstance);
		client.disconnect();
	}

	/**
	 * Handle membership events	
	 */
	@Override
	public void distributedInstanceEvent(final DistributedInstanceEvent event) {
		if(event instanceof DistributedInstanceAddEvent) {
			createConnection(event.getInstance());
		} else if(event instanceof DistributedInstanceDeleteEvent) {
			terminateConnection(event.getInstance());
		} else {
			logger.warn("Unknown event: " + event);
		}
	}
	
	//===============================================================
	// Test * Test * Test * Test * Test * Test * Test * Test
	//===============================================================
	public static void main(final String[] args) throws InterruptedException, ExecutionException {
		final String GROUP = "2_TESTGROUP";
		final String TABLE = "2_TESTGROUP_DATA";
		
		final Collection<String> zookeeperNodes = new ArrayList<String>();
		zookeeperNodes.add("node1:2181");
		final ScalephantCluster scalephantCluster = new ScalephantCluster(zookeeperNodes, "mycluster");
		scalephantCluster.connect();
		
		// Recreate distribution group
		final ClientOperationFuture futureDelete = scalephantCluster.deleteDistributionGroup(GROUP);
		futureDelete.get();
		final ClientOperationFuture futureCreate = scalephantCluster.createDistributionGroup(GROUP, (short) 2);
		futureCreate.get();
		
		// Insert the tuples
		final Random bbBoxRandom = new Random();
		for(int i = 0; i < 100000; i++) {
			final float x = (float) Math.abs(bbBoxRandom.nextFloat() % 100000.0 * 1000);
			final float y = (float) Math.abs(bbBoxRandom.nextFloat() % 100000.0 * 1000);
			
			final BoundingBox boundingBox = new BoundingBox(x, x+1, y, y+1);
			
			System.out.println("Inserting Tuple: " + boundingBox);
			
			scalephantCluster.insertTuple(TABLE, new Tuple(Integer.toString(i), boundingBox, "abcdef".getBytes()));
		}
		
		Thread.sleep(10000000);
	}

}
