package de.fernunihagen.dna.jkn.scalephant.network.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fernunihagen.dna.jkn.scalephant.Const;
import de.fernunihagen.dna.jkn.scalephant.ScalephantConfiguration;
import de.fernunihagen.dna.jkn.scalephant.ScalephantConfigurationManager;
import de.fernunihagen.dna.jkn.scalephant.distribution.DistributionGroupCache;
import de.fernunihagen.dna.jkn.scalephant.distribution.DistributionGroupName;
import de.fernunihagen.dna.jkn.scalephant.distribution.DistributionRegion;
import de.fernunihagen.dna.jkn.scalephant.distribution.ZookeeperClient;
import de.fernunihagen.dna.jkn.scalephant.distribution.ZookeeperClientFactory;
import de.fernunihagen.dna.jkn.scalephant.distribution.ZookeeperException;
import de.fernunihagen.dna.jkn.scalephant.distribution.membership.DistributedInstance;
import de.fernunihagen.dna.jkn.scalephant.distribution.membership.MembershipConnectionService;
import de.fernunihagen.dna.jkn.scalephant.distribution.nameprefix.NameprefixInstanceManager;
import de.fernunihagen.dna.jkn.scalephant.distribution.nameprefix.NameprefixMapper;
import de.fernunihagen.dna.jkn.scalephant.network.NetworkConnectionState;
import de.fernunihagen.dna.jkn.scalephant.network.NetworkConst;
import de.fernunihagen.dna.jkn.scalephant.network.NetworkHelper;
import de.fernunihagen.dna.jkn.scalephant.network.NetworkPackageDecoder;
import de.fernunihagen.dna.jkn.scalephant.network.client.ClientOperationFuture;
import de.fernunihagen.dna.jkn.scalephant.network.client.ScalephantClient;
import de.fernunihagen.dna.jkn.scalephant.network.packages.NetworkResponsePackage;
import de.fernunihagen.dna.jkn.scalephant.network.packages.request.CreateDistributionGroupRequest;
import de.fernunihagen.dna.jkn.scalephant.network.packages.request.DeleteDistributionGroupRequest;
import de.fernunihagen.dna.jkn.scalephant.network.packages.request.DeleteTableRequest;
import de.fernunihagen.dna.jkn.scalephant.network.packages.request.DeleteTupleRequest;
import de.fernunihagen.dna.jkn.scalephant.network.packages.request.InsertTupleRequest;
import de.fernunihagen.dna.jkn.scalephant.network.packages.request.QueryBoundingBoxRequest;
import de.fernunihagen.dna.jkn.scalephant.network.packages.request.QueryKeyRequest;
import de.fernunihagen.dna.jkn.scalephant.network.packages.request.QueryTimeRequest;
import de.fernunihagen.dna.jkn.scalephant.network.packages.request.TransferSSTableRequest;
import de.fernunihagen.dna.jkn.scalephant.network.packages.response.ErrorResponse;
import de.fernunihagen.dna.jkn.scalephant.network.packages.response.ListTablesResponse;
import de.fernunihagen.dna.jkn.scalephant.network.packages.response.MultipleTupleEndResponse;
import de.fernunihagen.dna.jkn.scalephant.network.packages.response.MultipleTupleStartResponse;
import de.fernunihagen.dna.jkn.scalephant.network.packages.response.SuccessResponse;
import de.fernunihagen.dna.jkn.scalephant.network.packages.response.TupleResponse;
import de.fernunihagen.dna.jkn.scalephant.network.routing.RoutingHeader;
import de.fernunihagen.dna.jkn.scalephant.network.routing.RoutingHeaderParser;
import de.fernunihagen.dna.jkn.scalephant.storage.StorageInterface;
import de.fernunihagen.dna.jkn.scalephant.storage.StorageManager;
import de.fernunihagen.dna.jkn.scalephant.storage.StorageManagerException;
import de.fernunihagen.dna.jkn.scalephant.storage.entity.BoundingBox;
import de.fernunihagen.dna.jkn.scalephant.storage.entity.SSTableName;
import de.fernunihagen.dna.jkn.scalephant.storage.entity.Tuple;

public class ClientConnectionHandler implements Runnable {
	
	/**
	 * The client socket
	 */
	protected final Socket clientSocket;
	
	/**
	 * The output stream of the socket
	 */
	protected BufferedOutputStream outputStream;
	
	/**
	 * The input stream of the socket
	 */
	protected InputStream inputStream;
	
	/**
	 * The connection state
	 */
	protected volatile NetworkConnectionState connectionState;

	/**
	 * The thread pool
	 */
	protected final ThreadPoolExecutor threadPool;
	
	/**
	 * Number of pending requests
	 */
	public static int PENDING_REQUESTS = 25;
	
	/**
	 * The Logger
	 */
	private final static Logger logger = LoggerFactory.getLogger(ClientConnectionHandler.class);
	

	public ClientConnectionHandler(final Socket clientSocket) {
		this.clientSocket = clientSocket;
		connectionState = NetworkConnectionState.NETWORK_CONNECTION_OPEN;
		
		// Create a thread pool that blocks after submitting more than PENDING_REQUESTS
		final BlockingQueue<Runnable> linkedBlockingDeque = new LinkedBlockingDeque<Runnable>(PENDING_REQUESTS);
		threadPool = new ThreadPoolExecutor(1, PENDING_REQUESTS/2, 30, TimeUnit.SECONDS, 
				linkedBlockingDeque, new ThreadPoolExecutor.CallerRunsPolicy());
		
		try {
			outputStream = new BufferedOutputStream(clientSocket.getOutputStream());
			inputStream = new BufferedInputStream(clientSocket.getInputStream());
		} catch (IOException e) {
			inputStream = null;
			outputStream = null;
			connectionState = NetworkConnectionState.NETWORK_CONNECTION_CLOSED;
			logger.error("Exception while creating IO stream", e);
		}
	}

	/**
	 * Read the next package header from the socket
	 * @return The package header, wrapped in a ByteBuffer
	 * @throws IOException
	 */
	protected ByteBuffer readNextPackageHeader() throws IOException {
		final ByteBuffer bb = ByteBuffer.allocate(12);
		NetworkHelper.readExactlyBytes(inputStream, bb.array(), 0, bb.limit());
		
		final RoutingHeader routingHeader = RoutingHeaderParser.decodeRoutingHeader(inputStream);
		final byte[] routingHeaderBytes = RoutingHeaderParser.encodeHeader(routingHeader);
		
		final ByteBuffer header = ByteBuffer.allocate(bb.limit() + routingHeaderBytes.length);
		header.put(bb.array());
		header.put(routingHeaderBytes);
		
		return header;
	}

	/**
	 * Write a response package to the client
	 * @param responsePackage
	 */
	protected synchronized boolean writeResultPackage(final NetworkResponsePackage responsePackage) {
		
		final byte[] outputData = responsePackage.getByteArray();
		
		try {
			outputStream.write(outputData, 0, outputData.length);
			outputStream.flush();
			return true;
		} catch (IOException e) {
			logger.warn("Unable to write result package", e);
		}

		return false;
	}
	
	@Override
	public void run() {
		try {
			logger.debug("Handling new connection from: " + clientSocket.getInetAddress());

			while(connectionState == NetworkConnectionState.NETWORK_CONNECTION_OPEN) {
				handleNextPackage();
			}
			
			connectionState = NetworkConnectionState.NETWORK_CONNECTION_CLOSED;
			logger.info("Closing connection to: " + clientSocket.getInetAddress());
		} catch (IOException e) {
			// Ignore exception on closing sockets
			if(connectionState == NetworkConnectionState.NETWORK_CONNECTION_OPEN) {
				logger.error("Unable to read data from socket (state: " + connectionState + ")", e);
			}
		} catch(Throwable e) {
			logger.error("Got an expcetion during execution: ", e);
		}
		
		threadPool.shutdown();
		
		closeSocketNE();
	}

	/**
	 * Close the socket without throwing an exception
	 */
	protected void closeSocketNE() {
		try {
			clientSocket.close();
		} catch (IOException e) {
			// Ignore close exception
		}
	}
	
	/**
	 * Handle query package
	 * @param bb
	 * @param packageSequence
	 * @return
	 */
	protected boolean handleQuery(final ByteBuffer packageHeader, final short packageSequence) {
		
		final ByteBuffer encodedPackage = readFullPackage(packageHeader);
		final byte queryType = NetworkPackageDecoder.getQueryTypeFromRequest(encodedPackage);

		switch (queryType) {
			case NetworkConst.REQUEST_QUERY_KEY:
				handleKeyQuery(encodedPackage, packageSequence);
				break;
				
			case NetworkConst.REQUEST_QUERY_BBOX:
				handleBoundingBoxQuery(encodedPackage, packageSequence);
				break;
				
			case NetworkConst.REQUEST_QUERY_TIME:
				handleTimeQuery(encodedPackage, packageSequence);
				break;
	
			default:
				logger.warn("Unsupported query type: " + queryType);
				writeResultPackage(new ErrorResponse(packageSequence));
				return true;
		}

		return true;
	}
	
	/**
	 * Handle the transfer package. In contrast to other packages, this package
	 * type can become very large. Therefore, the data is not buffered into a byte 
	 * buffer. The network stream is directly passed to the decoder.
	 * 
	 * @param bb
	 * @param packageSequence
	 * @return
	 */
	protected boolean handleTransfer(final ByteBuffer packageHeader, final short packageSequence) {
		
		final long bodyLength = NetworkPackageDecoder.getBodyLengthFromRequestPackage(packageHeader);
		final ScalephantConfiguration configuration = ScalephantConfigurationManager.getConfiguration();
		
		try {
			TransferSSTableRequest.decodeTuple(packageHeader, bodyLength, configuration, inputStream);
		} catch (IOException e) {
			logger.warn("Exception while handling sstable transfer", e);
		}
		
		return true;
	}
	
	/**
	 * Create a new distribution group
	 * @param packageHeader
	 * @param packageSequence
	 * @return
	 */
	protected boolean handleCreateDistributionGroup(final ByteBuffer packageHeader, final short packageSequence) {
		final ByteBuffer encodedPackage = readFullPackage(packageHeader);
		
		final CreateDistributionGroupRequest createPackage = CreateDistributionGroupRequest.decodeTuple(encodedPackage);
		logger.info("Create distribution group: " + createPackage.getDistributionGroup());
		
		try {
			final ZookeeperClient zookeeperClient = ZookeeperClientFactory.getZookeeperClient();
			zookeeperClient.createDistributionGroup(createPackage.getDistributionGroup(), createPackage.getReplicationFactor());
			
			final DistributionRegion region = zookeeperClient.readDistributionGroup(createPackage.getDistributionGroup());
			
			final ScalephantConfiguration scalephantConfiguration = 
					ScalephantConfigurationManager.getConfiguration();

			final String localIp = scalephantConfiguration.getLocalip();
			final int localPort = scalephantConfiguration.getNetworkListenPort();
			
			final DistributedInstance intance = new DistributedInstance(localIp, localPort, Const.VERSION);
			zookeeperClient.addSystemToDistributionRegion(region, intance);
			zookeeperClient.setStateForDistributionGroup(region, DistributionRegion.STATE_ACTIVE);
			
			waitForDistributionGroupReady(createPackage.getDistributionGroup());
			
			writeResultPackage(new SuccessResponse(packageSequence));
		} catch (Exception e) {
			logger.warn("Error while create distribution group", e);
			writeResultPackage(new ErrorResponse(packageSequence));	
		}
		
		return true;
	}

	/**
	 * Wait until the newly created distribution group is ready
	 * @param createPackage
	 * @throws InterruptedException
	 */
	protected void waitForDistributionGroupReady(final String distributionGroupName)
			throws InterruptedException {

		final DistributionGroupName distributionGroup = new DistributionGroupName(distributionGroupName);
		final NameprefixMapper nameprefixMapper = NameprefixInstanceManager.getInstance(distributionGroup);

		// Wait for zookeeper calls to settle down
		int retryCounter = 0;
		while(nameprefixMapper.getAllNamePrefixes().isEmpty()) {
			Thread.sleep(500);
			retryCounter++;
			if(retryCounter > Const.OPERATION_RETRY) {
				break;
			}
		}
		
		if(retryCounter > Const.OPERATION_RETRY) {
			logger.warn("Waited, but distribution group " 
					+ distributionGroupName + " don't became ready, give up");
		}
	}
	
	/**
	 * Delete an existing distribution group
	 * @param packageHeader
	 * @param packageSequence
	 * @return
	 */
	protected boolean handleDeleteDistributionGroup(final ByteBuffer packageHeader, final short packageSequence) {
		final ByteBuffer encodedPackage = readFullPackage(packageHeader);
		
		final DeleteDistributionGroupRequest deletePackage = DeleteDistributionGroupRequest.decodeTuple(encodedPackage);
		logger.info("Delete distribution group: " + deletePackage.getDistributionGroup());
		
		try {
			// Delete in Zookeeper
			final ZookeeperClient zookeeperClient = ZookeeperClientFactory.getZookeeperClient();
			zookeeperClient.deleteDistributionGroup(deletePackage.getDistributionGroup());
			
			// Delete local stored data
			final DistributionGroupName distributionGroupName = new DistributionGroupName(deletePackage.getDistributionGroup());
			StorageInterface.deleteAllTablesInDistributionGroup(distributionGroupName);
			
			writeResultPackage(new SuccessResponse(packageSequence));
		} catch (Exception e) {
			logger.warn("Error while delete distribution group", e);
			writeResultPackage(new ErrorResponse(packageSequence));	
		}
		
		return true;
	}
	
	
	/**
	 * Handle the delete table call
	 * @param packageSequence 
	 * @return
	 */
	protected boolean handleDeleteTable(final ByteBuffer packageHeader, final short packageSequence) {
		
		final ByteBuffer encodedPackage = readFullPackage(packageHeader);
		
		final DeleteTableRequest deletePackage = DeleteTableRequest.decodeTuple(encodedPackage);
		final SSTableName requestTable = deletePackage.getTable();
		logger.info("Got delete call for table: " + requestTable);
		
		try {
			// Send the call to the storage manager
			final NameprefixMapper nameprefixManager = NameprefixInstanceManager.getInstance(requestTable.getDistributionGroupObject());
			final Collection<SSTableName> localTables = nameprefixManager.getAllNameprefixesWithTable(requestTable);
			
			for(final SSTableName ssTableName : localTables) {
				StorageInterface.deleteTable(ssTableName);	
			}
			
			writeResultPackage(new SuccessResponse(packageSequence));
		} catch (StorageManagerException e) {
			logger.warn("Error while delete tuple", e);
			writeResultPackage(new ErrorResponse(packageSequence));	
		}
		
		return true;
	}

	/**
	 * Handle a key query
	 * @param encodedPackage
	 * @param packageSequence
	 */
	protected void handleKeyQuery(final ByteBuffer encodedPackage,
			final short packageSequence) {
		
		final QueryKeyRequest queryKeyRequest = QueryKeyRequest.decodeTuple(encodedPackage);
		final SSTableName requestTable = queryKeyRequest.getTable();
		
		try {
			// Send the call to the storage manager
			final NameprefixMapper nameprefixManager = NameprefixInstanceManager.getInstance(requestTable.getDistributionGroupObject());
			final Collection<SSTableName> localTables = nameprefixManager.getAllNameprefixesWithTable(requestTable);
			
			for(final SSTableName ssTableName : localTables) {
				final StorageManager storageManager = StorageInterface.getStorageManager(ssTableName);
				final Tuple tuple = storageManager.get(queryKeyRequest.getKey());
				
				if(tuple != null) {
					writeResultPackage(new TupleResponse(packageSequence, requestTable.getFullname(), tuple));
					return;
				}
			}

		    writeResultPackage(new SuccessResponse(packageSequence));
			return;
			
		} catch (StorageManagerException e) {
			logger.warn("Got exception while scanning for key", e);
		}
		
		writeResultPackage(new ErrorResponse(packageSequence));
	}
	
	/**
	 * Handle a bounding box query
	 * @param encodedPackage
	 * @param packageSequence
	 */
	protected void handleBoundingBoxQuery(final ByteBuffer encodedPackage,
			final short packageSequence) {
		
		final QueryBoundingBoxRequest queryRequest = QueryBoundingBoxRequest.decodeTuple(encodedPackage);
		final SSTableName requestTable = queryRequest.getTable();
		
		try {
			// Send the call to the storage manager
			final NameprefixMapper nameprefixManager = NameprefixInstanceManager.getInstance(requestTable.getDistributionGroupObject());
			final Collection<SSTableName> localTables = nameprefixManager.getNameprefixesForRegionWithTable(queryRequest.getBoundingBox(), requestTable);

			writeResultPackage(new MultipleTupleStartResponse(packageSequence));

			for(final SSTableName ssTableName : localTables) {
				final StorageManager storageManager = StorageInterface.getStorageManager(ssTableName);
				final Collection<Tuple> resultTuple = storageManager.getTuplesInside(queryRequest.getBoundingBox());
				
				for(final Tuple tuple : resultTuple) {
					writeResultPackage(new TupleResponse(packageSequence, requestTable.getFullname(), tuple));
				}
			}

			writeResultPackage(new MultipleTupleEndResponse(packageSequence));

			return;
		} catch (StorageManagerException e) {
			logger.warn("Got exception while scanning for bbox", e);
		}
		
		writeResultPackage(new ErrorResponse(packageSequence));
	}
	
	/**
	 * Handle a time query
	 * @param encodedPackage
	 * @param packageSequence
	 */
	protected void handleTimeQuery(final ByteBuffer encodedPackage,
			final short packageSequence) {
		
		final QueryTimeRequest queryRequest = QueryTimeRequest.decodeTuple(encodedPackage);
		final SSTableName requestTable = queryRequest.getTable();
		
		try {
			final NameprefixMapper nameprefixManager = NameprefixInstanceManager.getInstance(requestTable.getDistributionGroupObject());
			final Collection<SSTableName> localTables = nameprefixManager.getAllNameprefixesWithTable(requestTable);
			
			writeResultPackage(new MultipleTupleStartResponse(packageSequence));

			for(final SSTableName ssTableName : localTables) {
				final StorageManager storageManager = StorageInterface.getStorageManager(ssTableName);
				final Collection<Tuple> resultTuple = storageManager.getTuplesAfterTime(queryRequest.getTimestamp());

				for(final Tuple tuple : resultTuple) {
					writeResultPackage(new TupleResponse(packageSequence, requestTable.getFullname(), tuple));
				}
			}
			writeResultPackage(new MultipleTupleEndResponse(packageSequence));

			return;
		} catch (StorageManagerException e) {
			logger.warn("Got exception while scanning for time", e);
		}
		
		writeResultPackage(new ErrorResponse(packageSequence));
	}

	/**
	 * Handle Insert tuple package
	 * @param bb
	 * @param packageSequence
	 * @return
	 */
	protected boolean handleInsertTuple(final ByteBuffer packageHeader, final short packageSequence) {
		final ByteBuffer encodedPackage = readFullPackage(packageHeader);
		
		try {
			final InsertTupleRequest insertTupleRequest = InsertTupleRequest.decodeTuple(encodedPackage);
			
			// Send the call to the storage manager
			final Tuple tuple = insertTupleRequest.getTuple();			
			final SSTableName requestTable = insertTupleRequest.getTable();
			
			final NameprefixMapper nameprefixManager = NameprefixInstanceManager.getInstance(requestTable.getDistributionGroupObject());
			final BoundingBox boundingBox = insertTupleRequest.getTuple().getBoundingBox();
			final Collection<SSTableName> localTables = nameprefixManager.getNameprefixesForRegionWithTable(boundingBox, requestTable);

			for(final SSTableName ssTableName : localTables) {
				final StorageManager storageManager = StorageInterface.getStorageManager(ssTableName);
				storageManager.put(tuple);
			}

			performPackageRoutingAsync(packageSequence, insertTupleRequest, boundingBox);
			
		} catch (Exception e) {
			logger.warn("Error while insert tuple", e);
			writeResultPackage(new ErrorResponse(packageSequence));	
		}
		
		return true;
	}

	/**
	 * Perform the routing task async
	 * @param packageSequence
	 * @param insertTupleRequest
	 * @param boundingBox
	 */
	protected void performPackageRoutingAsync(final short packageSequence, final InsertTupleRequest insertTupleRequest, final BoundingBox boundingBox) {
	
		final Runnable routeRunable = new Runnable() {
			@Override
			public void run() {
				boolean routeResult;
				try {
					routeResult = routePackage(packageSequence, insertTupleRequest, boundingBox);
					
					if(routeResult) {
						writeResultPackage(new SuccessResponse(packageSequence));
					} else {
						writeResultPackage(new ErrorResponse(packageSequence));
					}
				} catch (ZookeeperException | InterruptedException | ExecutionException e) {
					logger.warn("Exception while routing package", e);
				}
			}
		};
		
		// Submit the runnable to our pool
		if(threadPool.isTerminating()) {
			logger.warn("Thread pool is shutting down, don't route package: " + packageSequence);
			writeResultPackage(new ErrorResponse(packageSequence));
		} else {
			threadPool.submit(routeRunable);
		}
	}

	/**
	 * Route the package to the next hop
	 * @param packageSequence
	 * @param insertTupleRequest
	 * @param boundingBox
	 * @return
	 * @throws ZookeeperException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 */
	protected boolean routePackage(final short packageSequence, final InsertTupleRequest insertTupleRequest,
			final BoundingBox boundingBox) throws ZookeeperException, InterruptedException, ExecutionException {
		
		// Create a new routing header or dispatch to next system
		prepareRountingHeader(insertTupleRequest, boundingBox);
		
		if(insertTupleRequest.getRoutingHeader().reachedFinalInstance()) {
			return true;
		} 
		
		final DistributedInstance receiver = insertTupleRequest.getRoutingHeader().getHopInstance();
		final ScalephantClient connection = MembershipConnectionService.getInstance().getConnectionForInstance(receiver);
		
		if(connection == null) {
			logger.error("Unable to get a connection to system: " + receiver);
			return false;
		} 
		
		final ClientOperationFuture insertFuture = connection.insertTuple(insertTupleRequest);
		insertFuture.waitForAll();
		
		if(insertFuture.isFailed()) {
			return false;
		} else {
			return true;
		}
	}

	/**
	 * Prepare the routing header for the next hop
	 * @param insertTupleRequest
	 * @param boundingBox
	 * @throws ZookeeperException
	 */
	protected void prepareRountingHeader(final InsertTupleRequest insertTupleRequest, final BoundingBox boundingBox) throws ZookeeperException {
		if(! insertTupleRequest.getRoutingHeader().isRoutedPackage()) {
			// Unrouted package: Create routing list
			final String distributionGroup = insertTupleRequest.getTable().getDistributionGroup();
			final ZookeeperClient zookeeperClient = ZookeeperClientFactory.getZookeeperClient();
			final DistributionRegion distributionRegion = DistributionGroupCache.getGroupForGroupName(distributionGroup, zookeeperClient);
			final List<DistributedInstance> instances = distributionRegion.getSystemsForBoundingBox(boundingBox);
			
			// Remove the local instance
			instances.remove(ZookeeperClientFactory.getLocalInstanceName(ScalephantConfigurationManager.getConfiguration()));
			
			final RoutingHeader routingHeader = new RoutingHeader(true, (short) 0, instances);
			insertTupleRequest.replaceRoutingHeader(routingHeader);
		} else { 
			// Routed package: dispatch to next hop
			insertTupleRequest.getRoutingHeader().dispatchToNextHop();
		}
	}

	/**
	 * Handle list tables package
	 * @param bb
	 * @param packageSequence
	 * @return
	 */
	protected boolean handleListTables(final ByteBuffer packageHeader, final short packageSequence) {
		readFullPackage(packageHeader);
		final List<SSTableName> allTables = StorageInterface.getAllTables();
		final ListTablesResponse listTablesResponse = new ListTablesResponse(packageSequence, allTables);
		writeResultPackage(listTablesResponse);
		
		return true;
	}

	/**
	 * Handle delete tuple package
	 * @param bb
	 * @param packageSequence
	 * @return
	 */
	protected boolean handleDeleteTuple(final ByteBuffer packageHeader, final short packageSequence) {

		final ByteBuffer encodedPackage = readFullPackage(packageHeader);

		final DeleteTupleRequest deleteTupleRequest = DeleteTupleRequest.decodeTuple(encodedPackage);
		final SSTableName requestTable = deleteTupleRequest.getTable();

		try {
			// Send the call to the storage manager
			final NameprefixMapper nameprefixManager = NameprefixInstanceManager.getInstance(requestTable.getDistributionGroupObject());
			final Collection<SSTableName> localTables = nameprefixManager.getAllNameprefixesWithTable(requestTable);

			for(final SSTableName ssTableName : localTables) {
				final StorageManager storageManager = StorageInterface.getStorageManager(ssTableName);
				storageManager.delete(deleteTupleRequest.getKey());
			}
			
			writeResultPackage(new SuccessResponse(packageSequence));
		} catch (StorageManagerException e) {
			logger.warn("Error while delete tuple", e);
			writeResultPackage(new ErrorResponse(packageSequence));	
		}

		return true;
	}	

	/**
	 * Read the full package. The total length of the package is read from the package header.
	 * @param packageHeader
	 * @return
	 */
	protected ByteBuffer readFullPackage(final ByteBuffer packageHeader) {
		final int bodyLength = (int) NetworkPackageDecoder.getBodyLengthFromRequestPackage(packageHeader);
		final int headerLength = packageHeader.limit();
		
		final ByteBuffer encodedPackage = ByteBuffer.allocate(headerLength + bodyLength);
		
		try {
			//System.out.println("Trying to read: " + bodyLength + " avail " + in.available());			
			encodedPackage.put(packageHeader.array());
			NetworkHelper.readExactlyBytes(inputStream, encodedPackage.array(), encodedPackage.position(), bodyLength);
		} catch (IOException e) {
			logger.error("IO-Exception while reading package", e);
			connectionState = NetworkConnectionState.NETWORK_CONNECTION_CLOSING;
			return null;
		}
		
		return encodedPackage;
	}

	/**
	 * Handle the next request package
	 * @throws IOException
	 */
	protected void handleNextPackage() throws IOException {
		final ByteBuffer packageHeader = readNextPackageHeader();

		final short packageSequence = NetworkPackageDecoder.getRequestIDFromRequestPackage(packageHeader);
		final byte packageType = NetworkPackageDecoder.getPackageTypeFromRequest(packageHeader);
		
		boolean readFurtherPackages = true;
		
		switch (packageType) {
			case NetworkConst.REQUEST_TYPE_DISCONNECT:
				logger.info("Got disconnect package, preparing for connection close: "  + clientSocket.getInetAddress());
				writeResultPackage(new SuccessResponse(packageSequence));
				readFurtherPackages = false;
				break;
				
			case NetworkConst.REQUEST_TYPE_DELETE_TABLE:
				if(logger.isDebugEnabled()) {
					logger.debug("Got delete table package");
				}
				readFurtherPackages = handleDeleteTable(packageHeader, packageSequence);
				break;
				
			case NetworkConst.REQUEST_TYPE_DELETE_TUPLE:
				if(logger.isDebugEnabled()) {
					logger.debug("Got delete tuple package");
				}
				readFurtherPackages = handleDeleteTuple(packageHeader, packageSequence);
				break;
				
			case NetworkConst.REQUEST_TYPE_LIST_TABLES:
				if(logger.isDebugEnabled()) {
					logger.debug("Got list tables request");
				}
				readFurtherPackages = handleListTables(packageHeader, packageSequence);
				break;
				
			case NetworkConst.REQUEST_TYPE_INSERT_TUPLE:
				if(logger.isDebugEnabled()) {
					logger.debug("Got insert tuple request");
				}
				readFurtherPackages = handleInsertTuple(packageHeader, packageSequence);
				break;
				
			case NetworkConst.REQUEST_TYPE_QUERY:
				if(logger.isDebugEnabled()) {
					logger.debug("Got query package");
				}
				readFurtherPackages = handleQuery(packageHeader, packageSequence);
				break;

			case NetworkConst.REQUEST_TYPE_TRANSFER:
				if(logger.isDebugEnabled()) {
					logger.debug("Got transfer package");
				}
				readFurtherPackages = handleTransfer(packageHeader, packageSequence);
				break;
				
			case NetworkConst.REQUEST_TYPE_CREATE_DISTRIBUTION_GROUP:
				if(logger.isDebugEnabled()) {
					logger.debug("Got create distribution group package");
				}
				readFurtherPackages = handleCreateDistributionGroup(packageHeader, packageSequence);
				break;
		
			case NetworkConst.REQUEST_TYPE_DELETE_DISTRIBUTION_GROUP:
				if(logger.isDebugEnabled()) {
					logger.debug("Got delete distribution group package");
				}
				readFurtherPackages = handleDeleteDistributionGroup(packageHeader, packageSequence);
				break;
				
			default:
				logger.warn("Got unknown package type, closing connection: " + packageType);
				connectionState = NetworkConnectionState.NETWORK_CONNECTION_CLOSING;
				break;
		}
		
		if(readFurtherPackages == false) {
			connectionState = NetworkConnectionState.NETWORK_CONNECTION_CLOSING;
		}	
	}
}