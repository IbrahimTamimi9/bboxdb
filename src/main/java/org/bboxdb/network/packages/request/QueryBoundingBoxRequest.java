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
package org.bboxdb.network.packages.request;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.bboxdb.Const;
import org.bboxdb.network.NetworkConst;
import org.bboxdb.network.NetworkPackageDecoder;
import org.bboxdb.network.packages.NetworkQueryRequestPackage;
import org.bboxdb.network.packages.PackageEncodeException;
import org.bboxdb.network.routing.RoutingHeader;
import org.bboxdb.storage.entity.BoundingBox;
import org.bboxdb.storage.entity.SSTableName;

public class QueryBoundingBoxRequest extends NetworkQueryRequestPackage {

	/**
	 * The name of the table
	 */
	protected final SSTableName table;

	/**
	 * The the query bounding box
	 */
	protected final BoundingBox box;
	
	/**
	 * Paging enables
	 */
	protected final boolean pagingEnabled;
	
	/**
	 * The max tuples per page
	 */
	protected final short tuplesPerPage;

	public QueryBoundingBoxRequest(final short sequenceNumber, final String table, 
			final BoundingBox box, final boolean pagingEnabled, final short tuplesPerPage) {
		
		super(sequenceNumber);
		
		this.table = new SSTableName(table);
		this.box = box;
		this.pagingEnabled = pagingEnabled;
		this.tuplesPerPage = tuplesPerPage;
	}

	@Override
	public void writeToOutputStream(final OutputStream outputStream) throws PackageEncodeException {

		try {
			final byte[] tableBytes = table.getFullnameBytes();
			final byte[] bboxBytes = box.toByteArray();
			
			final ByteBuffer bb = ByteBuffer.allocate(12);
			bb.order(Const.APPLICATION_BYTE_ORDER);
			
			bb.put(getQueryType());
			
			if(pagingEnabled) {
				bb.put((byte) 1);
			} else {
				bb.put((byte) 0);
			}
			
			bb.putShort(tuplesPerPage);
			
			bb.putShort((short) tableBytes.length);
			bb.put(NetworkConst.UNUSED_BYTE);
			bb.put(NetworkConst.UNUSED_BYTE);
			bb.putInt((int) bboxBytes.length);
			
			// Body length
			final long bodyLength = bb.capacity() + tableBytes.length + bboxBytes.length;
			
			// Unrouted package
			final RoutingHeader routingHeader = new RoutingHeader(false);
			appendRequestPackageHeader(sequenceNumber, bodyLength, routingHeader, 
					getPackageType(), outputStream);

			// Write body
			outputStream.write(bb.array());
			outputStream.write(tableBytes);
			outputStream.write(bboxBytes);
		} catch (IOException e) {
			throw new PackageEncodeException("Got exception while converting package into bytes", e);
		}	
	}
	
	/**
	 * Decode the encoded package into a object
	 * 
	 * @param encodedPackage
	 * @return
	 * @throws PackageEncodeException 
	 */
	public static QueryBoundingBoxRequest decodeTuple(final ByteBuffer encodedPackage) throws PackageEncodeException {
		final short sequenceNumber = NetworkPackageDecoder.getRequestIDFromRequestPackage(encodedPackage);
		
		final boolean decodeResult = NetworkPackageDecoder.validateRequestPackageHeader(encodedPackage, NetworkConst.REQUEST_TYPE_QUERY);
		
		if(decodeResult == false) {
			throw new PackageEncodeException("Unable to decode package");
		}
		
	    final byte queryType = encodedPackage.get();
	    
	    if(queryType != NetworkConst.REQUEST_QUERY_BBOX) {
	    	throw new PackageEncodeException("Wrong query type: " + queryType + " required type is: " + NetworkConst.REQUEST_QUERY_BBOX);
	    }
	    
	    boolean pagingEnabled = false;
	    if(encodedPackage.get() != 0) {
	    	pagingEnabled = true;
	    }
	    
	    final short tuplesPerPage = encodedPackage.getShort();
		final short tableLength = encodedPackage.getShort();
		
	    // 2 unused bytes
	    encodedPackage.get();
	    encodedPackage.get();
		
	    final int bboxLength = encodedPackage.getInt();

		final byte[] tableBytes = new byte[tableLength];
		encodedPackage.get(tableBytes, 0, tableBytes.length);
		final String table = new String(tableBytes);
		
		final byte[] bboxBytes = new byte[bboxLength];
		encodedPackage.get(bboxBytes, 0, bboxBytes.length);
		final BoundingBox boundingBox = BoundingBox.fromByteArray(bboxBytes);
		
		if(encodedPackage.remaining() != 0) {
			throw new PackageEncodeException("Some bytes are left after decoding: " + encodedPackage.remaining());
		}
		
		return new QueryBoundingBoxRequest(sequenceNumber, table, boundingBox, 
				pagingEnabled, tuplesPerPage);
	}

	@Override
	public byte getPackageType() {
		return NetworkConst.REQUEST_TYPE_QUERY;
	}

	@Override
	public byte getQueryType() {
		return NetworkConst.REQUEST_QUERY_BBOX;
	}
	
	public SSTableName getTable() {
		return table;
	}

	public BoundingBox getBoundingBox() {
		return box;
	}
	
	public short getTuplesPerPage() {
		return tuplesPerPage;
	}

	public boolean isPagingEnabled() {
		return pagingEnabled;
	}

}
