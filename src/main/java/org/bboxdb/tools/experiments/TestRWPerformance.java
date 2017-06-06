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
package org.bboxdb.tools.experiments;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.bboxdb.storage.entity.BoundingBox;
import org.bboxdb.storage.entity.Tuple;
import org.bboxdb.tools.experiments.tuplestore.TupleStore;
import org.bboxdb.tools.experiments.tuplestore.TupleStoreFactory;
import org.bboxdb.tools.generator.SyntheticDataGenerator;

import com.google.common.base.Stopwatch;

public class TestRWPerformance implements Runnable {

	/**
	 * The tuple store
	 */
	protected TupleStore tupleStore = null;
	
	/**
	 * The amount of tuples (one milion)
	 */
	public final static int TUPLES = 1000000;
	
	/** 
	 * The retry counter
	 */
	public final static int RETRY = 3;

	/**
	 * The name of the adapter
	 */
	private String adapterName;


	public TestRWPerformance(final String adapterName) throws Exception {
		this.adapterName = adapterName;
		System.out.println("#Using backend: " + adapterName);
	}

	@Override
	public void run() {
		
		final List<Integer> dataSizes = Arrays.asList(1024, 10240);
		System.out.println("#Size\tWrite\tRead");

		for(final int dataSize : dataSizes) {
			try {
				
				if(tupleStore != null) {
					tupleStore.close();
				}
				
				tupleStore = TupleStoreFactory.getTupleStore(adapterName);
				
				long timeRead = 0;
				long timeWrite = 0;
				
				final String data = SyntheticDataGenerator.getRandomString(dataSize);
	
				for(int i = 0; i < RETRY; i++) {
					timeWrite += writeTuples(data);
					timeRead += readTuples();
				}
				
				System.out.format("%d\t%d\t%d", dataSize, timeWrite / RETRY, timeRead / RETRY);
			} catch (Exception e) {
				System.out.println("Got exception: " + e);
			}
		}
	}

	/**
	 * Write the tuples
	 * @param data 
	 * @return 
	 * @throws IOException 
	 */
	protected long writeTuples(final String data) throws Exception {
		System.out.println("# Writing Tuples");
		final Stopwatch stopwatch = Stopwatch.createStarted();
		
		for(int i = 0; i < TUPLES; i++) {
			final Tuple tuple = new Tuple(Integer.toString(i), BoundingBox.EMPTY_BOX, data.getBytes());
			tupleStore.writeTuple(tuple);
		}
		
		return stopwatch.elapsed(TimeUnit.MILLISECONDS);
	}

	/**
	 * Read the tuples
	 * @return 
	 * @throws IOException 
	 */
	protected long readTuples() throws Exception {
		System.out.println("# Reading Tuples");
		final Stopwatch stopwatch = Stopwatch.createStarted();

		for(int i = 0; i < TUPLES; i++) {
			tupleStore.readTuple(Integer.toString(i));
		}
		
		return stopwatch.elapsed(TimeUnit.MILLISECONDS);
	}
	
	/**
	 * Main * Main * Main
	 * @throws IOException 
	 */
	public static void main(final String[] args) throws Exception {
		// Check parameter
		if(args.length != 1) {
			System.err.println("Usage: programm <adapter>");
			System.exit(-1);
		}
		
		final String adapter = Objects.requireNonNull(args[0]);
		
		if(! TupleStoreFactory.ALL_STORES.contains(adapter)) {
			System.err.println("Unknown adapter: " + adapter);
			System.exit(-1);
		}

		final TestRWPerformance testSplit = new TestRWPerformance(adapter);
		testSplit.run();
	}

}