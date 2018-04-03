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
package org.bboxdb.network.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bboxdb.commons.CloseableHelper;
import org.bboxdb.commons.ServiceState;
import org.bboxdb.commons.concurrent.ExceptionSafeRunnable;
import org.bboxdb.misc.BBoxDBConfiguration;
import org.bboxdb.misc.BBoxDBConfigurationManager;
import org.bboxdb.misc.BBoxDBService;
import org.bboxdb.storage.tuplestore.manager.TupleStoreManagerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkConnectionService implements BBoxDBService {
	
	/**
	 * The configuration
	 */
	private final BBoxDBConfiguration configuration = BBoxDBConfigurationManager.getConfiguration();

	/**
	 * Our thread pool to handle connections
	 */
	private ExecutorService threadPool;
	
	/**
	 * The connection handler state
	 */
	private final ServiceState state = new ServiceState();
	
	/**
	 * The connection dispatcher runnable
	 */
	private ConnectionDispatcher serverSocketDispatcher = null;
	
	/**
	 * The thread that listens on the server socket and dispatches
	 * incoming requests to the thread pool
	 */
	private Thread serverSocketDispatchThread = null;
	
	/**
	 * The storage reference
	 */
	private final TupleStoreManagerRegistry storageRegistry;
	
	/**
	 * The Logger
	 */
	private final static Logger logger = LoggerFactory.getLogger(NetworkConnectionService.class);
	
	public NetworkConnectionService(final TupleStoreManagerRegistry storageRegistry) {
		this.storageRegistry = storageRegistry;
	}
	
	/**
	 * Start the network connection handler
	 */
	public void init() {
		
		if(! state.isInNewState()) {
			logger.info("init() called on ready instance, ignoring: {}", state.getState());
			return;
		}
		
		try {
			state.dipatchToStarting();
			
			final int port = configuration.getNetworkListenPort();
			logger.info("Start the network connection handler on port: {}", port);
			
			if(threadPool == null) {
				threadPool = Executors.newFixedThreadPool(configuration.getNetworkConnectionThreads());
			}
			
			serverSocketDispatcher = new ConnectionDispatcher(port);
			serverSocketDispatchThread = new Thread(serverSocketDispatcher);
			serverSocketDispatchThread.start();
			serverSocketDispatchThread.setName("Connection dispatcher thread");
			
			state.dispatchToRunning();
		} catch(Exception e) {
			logger.error("Got exception, setting state to failed", e);
			state.dispatchToFailed(e);
			shutdown();
			throw e;
		}
	}
	
	/**
	 * Shutdown the network connection
	 */
	public synchronized void shutdown() {
		
		if(! state.isInRunningState()) {
			logger.info("shutdown() called non running instance, ignoring: {}", state.getState());
			return;
		}
		
		logger.info("Shutdown the network connection handler");
		state.dispatchToStopping();
		
		if(serverSocketDispatchThread != null) {
			serverSocketDispatcher.closeSocketNE();
			serverSocketDispatchThread.interrupt();	
			serverSocketDispatchThread = null;
			serverSocketDispatcher = null;
		}
		
		if(threadPool != null) {
			threadPool.shutdown();
			threadPool = null;
		}
		
		state.dispatchToTerminated();
	}
	
	/**
	 * The connection dispatcher
	 *
	 */
	class ConnectionDispatcher extends ExceptionSafeRunnable {

		/**
		 * The server socket
		 */
		private ServerSocket serverSocket;
		
		/**
		 * The listen port
		 */
		private final int port;
		
		public ConnectionDispatcher(final int port) {
			this.port = port;
		}

		@Override
		protected void beginHook() {
			logger.info("Starting new connection dispatcher");
		}

		@Override
		protected void endHook() {
			logger.info("Shutting down the connection dispatcher");
		}
		
		@Override
		public void runThread() {			
			try {
				serverSocket = new ServerSocket(port);
				serverSocket.setReuseAddress(true);
				
				while(isThreadActive()) {
					final Socket clientSocket = serverSocket.accept();
					handleConnection(clientSocket);
				}
				
			} catch(IOException e) {
				
				// Print exception only if the exception is really unexpected
				if(Thread.currentThread().isInterrupted() != true) {
					logger.error("Got an IO exception while reading from server socket ", e);
				}

			} finally {
				closeSocketNE();
			}
		}

		/**
		 * Is the server socket dispatcher active?
		 * @return
		 */
		private boolean isThreadActive() {
			
			if(Thread.currentThread().isInterrupted()) {
				return false;
			}
			
			if(serverSocket == null) {
				return false;
			}
			
			return true;
		}

		/**
		 * Close socket without an exception
		 */
		public void closeSocketNE() {
			logger.info("Close server socket on port: {}", port);
			CloseableHelper.closeWithoutException(serverSocket);
		}
		
		/**
		 * Dispatch the connection to the thread pool
		 * @param clientSocket
		 */
		private void handleConnection(final Socket clientSocket) {
			logger.debug("Got new connection from: {}", clientSocket.getInetAddress());
			threadPool.submit(new ClientConnectionHandler(storageRegistry, clientSocket));
		}
	}

	@Override
	public String getServicename() {
		return "Network connection handler";
	}
}