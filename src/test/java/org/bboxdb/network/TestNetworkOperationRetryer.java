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
package org.bboxdb.network;

import java.util.function.Consumer;

import org.bboxdb.misc.Const;
import org.bboxdb.network.client.NetworkOperationRetryer;
import org.bboxdb.network.packages.NetworkRequestPackage;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class TestNetworkOperationRetryer {

	/**
	 * The do nothing consumer
	 */
	protected Consumer<NetworkRequestPackage> doNothingConsumer = (p) -> {};
	
	@Test(expected=IllegalArgumentException.class)
	public void testPackageNotFound() {
		final NetworkOperationRetryer retryer 
			= new NetworkOperationRetryer(doNothingConsumer);
		
		retryer.handleFailure((short) 12);
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testPackageDuplicate() {
		final NetworkOperationRetryer retryer 
			= new NetworkOperationRetryer(doNothingConsumer);

		retryer.registerOperation((short) 12, null);
		retryer.registerOperation((short) 12, null);
	}
	
	@Test
	public void testPackageDuplicateWithClear() {
		final NetworkOperationRetryer retryer 
			= new NetworkOperationRetryer(doNothingConsumer);

		retryer.registerOperation((short) 12, null);
		retryer.clear();
		retryer.registerOperation((short) 12, null);
	}
	
	@Test
	public void successOnFirst() {
		final NetworkOperationRetryer retryer 
			= new NetworkOperationRetryer(doNothingConsumer);
		retryer.registerOperation((short) 12, null);
		retryer.handleSuccess((short) 12);
	}

	@Test
	public void retry() {
		@SuppressWarnings("unchecked")
		final Consumer<NetworkRequestPackage> consumer = Mockito.mock(Consumer.class);
		
		final NetworkOperationRetryer retryer 
			= new NetworkOperationRetryer(consumer);
		
		retryer.registerOperation((short) 12, null);
		
		final boolean result = retryer.handleFailure((short) 12);
		Assert.assertTrue(result);
		(Mockito.verify(consumer, Mockito.atLeastOnce())).accept(null);
	}
	
	@Test
	public void retryUntilEnd() {
		@SuppressWarnings("unchecked")
		final Consumer<NetworkRequestPackage> consumer = Mockito.mock(Consumer.class);
		
		final NetworkOperationRetryer retryer 
			= new NetworkOperationRetryer(consumer);
		
		retryer.registerOperation((short) 12, null);
		
		for(int i = 0; i< Const.OPERATION_RETRY; i++) {
			final boolean result = retryer.handleFailure((short) 12);
			Assert.assertTrue(result);
			(Mockito.verify(consumer, Mockito.times(i + 1))).accept(null);
		}
		
		final boolean result = retryer.handleFailure((short) 12);
		Assert.assertFalse(result);
		
		// Failed, we assume the operation is removed
		retryer.registerOperation((short) 12, null);
	}
}