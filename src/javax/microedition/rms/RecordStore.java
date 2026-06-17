/*
	This file is part of FreeJ2ME.

	FreeJ2ME is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	FreeJ2ME is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with FreeJ2ME.  If not, see http://www.gnu.org/licenses/
*/
package javax.microedition.rms;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Vector;

import org.recompile.mobile.Base64Util;
import org.recompile.mobile.Mobile;

public class RecordStore
{

	public static final int AUTHMODE_APPLEVEL = 2; // This probably won't ever be used, but since RecordStore now has quite a few bits from MIDP 3.0, having this defined wouldn't hurt
	public static final int AUTHMODE_ANY = 1;
	public static final int AUTHMODE_PRIVATE = 0;

	protected RecordStore thisStore;

	private static final String RMS_VERSION = "1.0.0";
	private static final Object STORE_LOCK = new Object();
	private static final Map<String, StoreState> openStores = new HashMap<String, StoreState>();

	private String name;
	private String basename, suitename, vendorname, password;
	private boolean writable;
	private int authmode;
	private String rmsPath;
	private String rmsFile;
	private File file;
	private int scratchPadIndex = 0; // DoJa-only, used to differentiate between multiple scratchpads when writing
	private Vector<RecordListener> listeners;
	private StoreState state;
	private boolean recordStoreIsOpen = false;

	private static final class StoreState
	{
		String key;
		String name;
		String basename;
		String suitename;
		String vendorname;
		String password;
		String rmsPath;
		String rmsFile;
		File file;
		boolean writablebyothers;
		int authmode;
		int version;
		int nextid;
		long lastModified;
		Vector<byte[]> records;
		Vector<Integer> recordIds;
		Vector<Integer> recordTags;
		Vector<RecordStore> openHandles;

		StoreState(String key, String name, String basename, String suitename, String vendorname, String password, String rmsPath, String rmsFile, int authmode, boolean writable)
		{
			this.key = key;
			this.name = name;
			this.basename = basename;
			this.suitename = suitename;
			this.vendorname = vendorname;
			this.password = password;
			this.rmsPath = rmsPath;
			this.rmsFile = rmsFile;
			this.file = new File(rmsFile);
			this.authmode = authmode;
			this.writablebyothers = writable && authmode == AUTHMODE_ANY;
			this.version = 0;
			this.nextid = 1;
			this.lastModified = 0;
			this.records = new Vector<byte[]>();
			this.recordIds = new Vector<Integer>();
			this.recordTags = new Vector<Integer>();
			this.openHandles = new Vector<RecordStore>();
			resetVectors();
		}

		void resetVectors()
		{
			records.removeAllElements();
			recordIds.removeAllElements();
			recordTags.removeAllElements();

			records.add(new byte[]{}); // dummy record (record ids start at 1)
			recordIds.add(Integer.valueOf(0));
			recordTags.add(Integer.valueOf(0));
		}
	}

	private RecordStore(String recordStoreName, boolean createIfNecessary, String vendorname, String suitename, int authmode, boolean writable, String password) throws RecordStoreException, RecordStoreNotFoundException, SecurityException
	{
		if(recordStoreName == null) { throw new NullPointerException("RecordStore received a null argument"); }
		if(recordStoreName.length() == 0) { throw(new RecordStoreException("The record name:'"+ recordStoreName +"' is not valid")); }

		this.name = recordStoreName;
		this.password = password;
		this.writable = writable;
		this.authmode = authmode;
		this.vendorname = vendorname;
		this.suitename = suitename;
		this.listeners = new Vector<RecordListener>();
		this.basename = generateBaseName(vendorname, suitename, recordStoreName);
		this.rmsPath = buildRmsPath(suitename);
		this.rmsFile = rmsPath + "/" + basename + ".rms";
		this.file = new File(rmsFile);

		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> RecordStore "+basename);

		try
		{
			File rmsDir = new File(rmsPath);
			if (!rmsDir.exists()) { rmsDir.mkdirs(); }
		}
		catch (Exception e)
		{
			Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + e.getMessage());
			throw(new RecordStoreException("Problem Creating Record Store Path "+rmsPath));
		}

		String key = buildStoreKey(rmsFile);

		synchronized (STORE_LOCK)
		{
			StoreState cached = openStores.get(key);
			if (cached == null)
			{
				cached = new StoreState(key, recordStoreName, basename, suitename, vendorname, password, rmsPath, rmsFile, authmode, writable);
				this.state = cached;
				loadState(createIfNecessary);
				openStores.put(key, cached);
			}
			else
			{
				this.state = cached;
			}

			if(!Mobile.getPlatform().loader.suitename.equals(this.state.suitename) && this.state.authmode != AUTHMODE_ANY)
			{
				if (this.state.openHandles.size() == 0) { openStores.remove(key); }
				this.state = null;
				throw new SecurityException("This suite does not have authorization to access the requested RecordStore:" + name);
			}

			this.state.openHandles.add(this);
			this.recordStoreIsOpen = true;
		}

		thisStore = this;
	}

	private static String buildStoreKey(String rmsFile)
	{
		return new File(rmsFile).getAbsolutePath();
	}

	private static String buildRmsPath(String suitename) throws RecordStoreException
	{
		try
		{
			// For ISO-8859-1 encodings, we'll use UTF-8 for save paths, helps with chinese and special characters
			return new String((Mobile.getPlatform().dataPath + "./rms/" + suitename).getBytes(System.getProperty("file.encoding")), System.getProperty("file.encoding").equals(Mobile.supportedEncodings[Mobile.ISO_8859_1]) ? "UTF-8" : Mobile.textEncoding);
		}
		catch (UnsupportedEncodingException e)
		{
			throw new RecordStoreException("Problem Creating Record Store Path for " + suitename);
		}
	}

	private void checkOpen() throws RecordStoreNotOpenException
	{
		if(!recordStoreIsOpen || state == null) { throw new RecordStoreNotOpenException("Record Store is not open at this time"); }
	}

	private void checkWritable() throws SecurityException
	{
		if(!Mobile.getPlatform().loader.suitename.equals(state.suitename) && !state.writablebyothers) { throw new SecurityException("This suite does not have write access to this RecordStore"); }
	}

	private int indexOfRecordId(int recordId)
	{
		if(recordId == 0) { recordId = 1; }
		return state.recordIds.indexOf(Integer.valueOf(recordId));
	}

	private byte[] recordDataById(int recordId) throws InvalidRecordIDException, RecordStoreNotOpenException
	{
		checkOpen();
		int idx = indexOfRecordId(recordId);
		if(idx <= 0) { throw new InvalidRecordIDException("getRecord: Invalid Record ID: " + recordId); }
		byte[] t = state.records.get(idx);
		return t == null ? null : t.clone();
	}

	// We don't add anything to recordIds here, as all this does is load records when a recordStore is opened (recordIds are loaded right after lastModified)
	private void loadRecord(byte[] data, int offset, int numBytes)
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "loading Record...");
		byte[] rec = Arrays.copyOfRange(data, offset, offset+numBytes);
		if(rec==null) { rec = new byte[]{}; }
		state.records.addElement(rec);
	}

	private int getUInt16(byte[] data, int offset)
	{
		int out = 0;
		out |= (((int)data[offset])   & 0xFF) << 8;
		out |= (((int)data[offset+1]) & 0xFF);
		return out;
	}

	private int getUint32(byte[] data, int offset)
	{
		int out = 0;
		out |= (((int)data[offset])   & 0xFF) << 24;
		out |= (((int)data[offset+1]) & 0xFF) << 16;
		out |= (((int)data[offset+2]) & 0xFF) << 8;
		out |= (((int)data[offset+3]) & 0xFF);
		return out;
	}

	private void setUInt16(byte[] data, int offset, int val)
	{
		data[offset]   = (byte)((val>>8) & 0xFF);
		data[offset+1] = (byte)((val)    & 0xFF);
	}

	private byte[] setUInt32(int offset, int val)
	{
		byte[] data = new byte[4];
		data[offset]   = (byte)((val>>24)  & 0xFF);
		data[offset+1] = (byte)((val>>16)  & 0xFF);
		data[offset+2] = (byte)((val>>8)   & 0xFF);
		data[offset+3] = (byte)((val)      & 0xFF);
		return data;
	}

	private long getLong(byte[] data, int offset)
	{
		long out = 0;
		out |= (((long)data[offset])   & 0xFF) << 56;
		out |= (((long)data[offset+1]) & 0xFF) << 48;
		out |= (((long)data[offset+2]) & 0xFF) << 40;
		out |= (((long)data[offset+3]) & 0xFF) << 32;
		out |= (((long)data[offset+4]) & 0xFF) << 24;
		out |= (((long)data[offset+5]) & 0xFF) << 16;
		out |= (((long)data[offset+6]) & 0xFF) << 8;
		out |= (((long)data[offset+7]) & 0xFF);
		return out;
	}
	
	private void setLong(byte[] data, int offset, long val)
	{
		data[offset]   = (byte)((val>>56) & 0xFF);
		data[offset+1] = (byte)((val>>48) & 0xFF);
		data[offset+2] = (byte)((val>>40) & 0xFF);
		data[offset+3] = (byte)((val>>32) & 0xFF);
		data[offset+4] = (byte)((val>>24) & 0xFF);
		data[offset+5] = (byte)((val>>16) & 0xFF);
		data[offset+6] = (byte)((val>>8)  & 0xFF);
		data[offset+7] = (byte)((val)     & 0xFF);
	}

	public int addRecord(byte[] data, int offset, int numBytes) throws RecordStoreException, RecordStoreFullException, SecurityException
	{
		return addRecord(data, offset, numBytes, 0);
	}

	public int addRecord(byte[] data, int offset, int numBytes, int tag) throws RecordStoreException, RecordStoreFullException, SecurityException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Add Record "+(state == null ? -1 : state.nextid)+ " to "+name + " with tag " + tag + ", length " + numBytes + " and data " + (data != null? Arrays.toString(data) : "null"));

		checkOpen();
		checkWritable();
		if (data == null && numBytes > 0) { throw new NullPointerException("Cannot add record, as it is null"); }
		if(offset < 0 || numBytes < 0 || (data != null && offset + numBytes > data.length)) { throw new ArrayIndexOutOfBoundsException("Tried to access invalid record data position"); }

		int recordId;
		synchronized (state)
		{
			byte[] rec = new byte[]{};
			if(data != null && numBytes != 0) { rec = Arrays.copyOfRange(data, offset, offset+numBytes); }

			recordId = state.nextid;
			state.records.addElement(rec);
			state.recordIds.addElement(Integer.valueOf(recordId));
			state.recordTags.addElement(Integer.valueOf(tag));
			state.lastModified = System.currentTimeMillis();
			state.version++;
			state.nextid++;
			saveRecordStoreRecord(recordId);
		}

		notifyRecordAdded(recordId);
		return recordId;
	}

	public void closeRecordStore() throws RecordStoreNotOpenException
	{ 
		checkOpen();

		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Close Record");

		synchronized (STORE_LOCK)
		{
			recordStoreIsOpen = false;
			listeners.removeAllElements();
			if(state != null)
			{
				state.openHandles.remove(this);
				if(state.openHandles.size() == 0)
				{
					Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> No more stores opened for " + name + ", removing cached state.");
					openStores.remove(state.key);
				}
				state = null;
			}
		}
	}

	public void deleteRecord(int recordId) throws RecordStoreException, SecurityException
	{
		checkOpen();
		checkWritable();
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Delete Record " + recordId);

		synchronized (state)
		{
			int idx = indexOfRecordId(recordId);
			if(idx <= 0) { throw new InvalidRecordIDException("deleteRecord: Invalid Record ID: " + recordId); }

			state.records.remove(idx);
			state.recordTags.remove(idx);
			state.recordIds.remove(idx);
			state.lastModified = System.currentTimeMillis();
			state.version++;
			saveRecordStoreDeletedRecord(recordId);
		}

		notifyRecordDeleted(recordId);
	}

	// This should only delete records that are tied to the current MIDlet suite
	public static void deleteRecordStore(String recordStoreName) throws RecordStoreException
	{
		if(recordStoreName == null) { throw new NullPointerException("RecordStore received a null argument"); }
		if(recordStoreName.length() == 0) { throw(new RecordStoreException("The record name:'"+ recordStoreName +"' is not valid")); }

		String suite = Mobile.getPlatform().loader.suitename;
		String vendor = Mobile.getPlatform().loader.vendorname;
		String path = buildRmsPath(suite);
		String base = generateBaseName(vendor, suite, recordStoreName);
		String key = buildStoreKey(path + "/" + base + ".rms");

		synchronized (STORE_LOCK)
		{
			StoreState cached = openStores.get(key);
			if(cached != null && cached.openHandles.size() > 0) { throw new RecordStoreException("Cannot delete an open record store"); }
			openStores.remove(key);
		}

		boolean found = false;
		try
		{
			Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "Deleting RecordStore "+recordStoreName);
			File folder = new File(path);
			File[] files = folder.listFiles();
			if (files != null) 
			{
				for (int i = 0; i < files.length; i++)
				{
					File f = files[i];
					String fileName = f.getName();
					if (f.isFile() && (fileName.equals(base + ".rms") || fileName.startsWith(base + ".") || fileName.equals(recordStoreName)))
					{
						found = true;
						boolean deleted = f.delete();
						if (deleted) { Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": Deleted " + f.getName()); }
						else { Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": Failed to delete " + f.getName()); }
					}
				}
			}
		}
		catch (Exception e)
		{
			Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "Problem deleting RecordStore "+recordStoreName);
			e.printStackTrace();
			throw new RecordStoreException("Could not delete the requested RecordStore");
		}

		if(!found) { throw new RecordStoreNotFoundException("Could not find the requested RecordStore"); }
	}

	public RecordEnumeration enumerateRecords(RecordFilter filter, RecordComparator comparator, boolean keepUpdated)
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "RecordStore.enumerateRecords");
		return new enumeration(filter, comparator, keepUpdated);
	}

	public RecordEnumeration enumerateRecords(RecordFilter filter, RecordComparator comparator, boolean keepUpdated, int[] tags)
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "RecordStore.enumerateRecords with tags");
		return new enumeration(filter, comparator, keepUpdated, tags);
	}

	public long getLastModified() { return state == null ? 0 : state.lastModified; }

	public String getName() { return name; }

	public int getNextRecordID() throws RecordStoreNotOpenException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> getNextRecordID");
		checkOpen();
		return state.nextid;
	}

	// As noted in the RecordStore Constructor, Record IDs start from 1, so the very first position (0) of the record vector is just padding, hence why this returns size-1;
	public int getNumRecords() throws RecordStoreNotOpenException
	{
		checkOpen();
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> getNumRecords:" + (state.recordIds.size()-1));
		return state.recordIds.size()-1;
	}

	public byte[] getRecord(int recordId) throws InvalidRecordIDException, RecordStoreNotOpenException, RecordStoreException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> getRecord("+recordId+")");
		return recordDataById(recordId);
	}

	public int getRecord(int recordId, byte[] buffer, int offset) throws InvalidRecordIDException, RecordStoreNotOpenException, RecordStoreException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> getRecord(" + recordId + ", " + buffer + ", " + offset + ")");
		checkOpen();
		if(buffer == null) { throw new NullPointerException("Buffer cannot be null"); }
		if(offset < 0 || offset > buffer.length) { throw new ArrayIndexOutOfBoundsException("Invalid buffer offset"); }
		byte[] temp = recordDataById(recordId);
		if(temp.length > buffer.length-offset) { throw new ArrayIndexOutOfBoundsException("Record data won't fit on the provided buffer"); }
		System.arraycopy(temp, 0, buffer, offset, temp.length);
		return temp.length;
	}

	public int getTag(int recordId) throws InvalidRecordIDException, RecordStoreNotOpenException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> getTag("+recordId+")");
		checkOpen();
		int idx = indexOfRecordId(recordId);
		if(idx <= 0) { throw new InvalidRecordIDException("getRecord: Invalid Record ID: "+recordId); }
		return state.recordTags.get(idx).intValue();
	}

	public int getRecordSize(int recordId) throws InvalidRecordIDException, RecordStoreNotOpenException, RecordStoreException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Get Record Size");
		checkOpen();
		int idx = indexOfRecordId(recordId);
		if(idx <= 0) { throw new InvalidRecordIDException("getRecord: Invalid Record ID: "+recordId); }
		return state.records.get(idx).length;
	}

	public int getSize() throws RecordStoreNotOpenException
	{ 
		checkOpen();
		int size = 0;
		for(int i = 1; i < state.records.size(); i++) { if(state.records.get(i) != null) { size += state.records.get(i).length; } }
		return size;
	}

	// 16MiB minus whatever size the RecordStore is currently occupying. Whould be more than enough for everything given how limited those devices were.
	public int getSizeAvailable() throws RecordStoreNotOpenException
	{
		checkOpen();
		int size = 0;
		for(int i = 1; i < state.records.size(); i++) { if(state.records.get(i) != null) { size += state.records.get(i).length; } }
		return 16777216 - size; 
	}

	public int getVersion() { return state == null ? 0 : state.version; }

	public static String[] listRecordStores()
	{		
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "List Record Stores");
		String path = null;
		try
		{
			path = buildRmsPath(Mobile.getPlatform().loader.suitename);
			File rmsDir = new File(path);
			if (!rmsDir.exists()) { rmsDir.mkdirs(); }
		}
		catch (Exception e) { return null; }
		
		try 
		{
			File folder = new File(path);
			File[] files = folder.listFiles();
			if (files != null) 
			{
				List<String> outList = new ArrayList<String>();
				for (int i = 0; i < files.length; i++)
				{
					File f = files[i];
					if (f.isFile() && f.getName().endsWith(".rms"))
					{
						String outName = returnRecordStoreName(path + "/" + f.getName());
						if(outName != null) { outList.add(outName); }
					}
				}
				return outList.size() == 0 ? null : outList.toArray(new String[0]);
			}
		} 
		catch (Exception e) { e.printStackTrace(); }

		return null;
	}

	public static RecordStore openRecordStore(String recordStoreName, boolean createIfNecessary) throws RecordStoreException, RecordStoreNotFoundException, SecurityException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "Open Record Store A "+ createIfNecessary + ": " + recordStoreName);
		return new RecordStore(recordStoreName, createIfNecessary, Mobile.getPlatform().loader.vendorname, Mobile.getPlatform().loader.suitename, AUTHMODE_PRIVATE, true, "");
	}

	public static RecordStore openRecordStore(String recordStoreName, boolean createIfNecessary, int authmode, boolean writable) throws RecordStoreException, RecordStoreNotFoundException, SecurityException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "Open Record Store B "+ createIfNecessary + ": " + recordStoreName);
		return new RecordStore(recordStoreName, createIfNecessary, Mobile.getPlatform().loader.vendorname, Mobile.getPlatform().loader.suitename, authmode, writable, "");
	}

	public static RecordStore openRecordStore(String recordStoreName, boolean createIfNecessary, int authmode, boolean writable, String password) throws RecordStoreException, RecordStoreNotFoundException, SecurityException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "Open Record Store C + pass,auth "+ createIfNecessary + ": " + recordStoreName);
		return new RecordStore(recordStoreName, createIfNecessary, Mobile.getPlatform().loader.vendorname, Mobile.getPlatform().loader.suitename, authmode, writable, password);
	}

	/* 
	 * These can open a record store from another vendor and suite, so default their access modes to PRIVATE and writable to false as these tokens will change in the constructor,
	 * based on the writable and authentication flags that the file was last saved with.
	 */
	public static RecordStore openRecordStore(String recordStoreName, String vendorName, String suiteName) throws RecordStoreException, RecordStoreNotFoundException, SecurityException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "Open Record Store D:" + recordStoreName);
		return new RecordStore(recordStoreName, false, vendorName, suiteName, AUTHMODE_PRIVATE, false, "");
	}

	public static RecordStore openRecordStore(String recordStoreName, String vendorName, String suiteName, String password) throws RecordStoreException, RecordStoreNotFoundException, SecurityException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "Open Record Store E + pass:" + recordStoreName);
		return new RecordStore(recordStoreName, false, vendorName, suiteName, AUTHMODE_PRIVATE, false, password);
	}

	public void addRecordListener(RecordListener listener) { if(listener != null && listeners != null && !listeners.contains(listener)) { listeners.add(listener); } }

	public void removeRecordListener(RecordListener listener) { if(listeners != null) { listeners.remove(listener); } }

	public void setMode(int authmode, boolean writable) throws SecurityException
	{  
		if(authmode != AUTHMODE_ANY && authmode != AUTHMODE_PRIVATE) { throw new IllegalArgumentException("Invalid authentication mode"); }
		if(state == null || !Mobile.getPlatform().loader.suitename.equals(state.suitename)) { throw new SecurityException("Cannot change another suite's recordStore mode"); }
		this.authmode = authmode;
		this.writable = writable;
		state.authmode = authmode;
		state.writablebyothers = writable && authmode == AUTHMODE_ANY;
		saveRecordStore();
	}

	public void setRecord(int recordId, byte[] newData, int offset, int numBytes) throws RecordStoreException, InvalidRecordIDException, SecurityException
	{
		setRecord(recordId, newData, offset, numBytes, 0);
	}

	public void setRecord(int recordId, byte[] newData, int offset, int numBytes, int tag) throws RecordStoreException, InvalidRecordIDException, SecurityException
	{
		Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Set Record "+recordId+" in "+name + " from " + offset + " to " + (offset+numBytes) +  " with tag " + tag);
		checkOpen();
		checkWritable();
		if(newData == null && numBytes > 0) { throw new NullPointerException("Cannot set record, as it is null"); }
		if(offset < 0 || numBytes < 0 || (newData != null && offset + numBytes > newData.length)) { throw new ArrayIndexOutOfBoundsException("Tried to access invalid record data position"); }

		synchronized (state)
		{
			int idx = indexOfRecordId(recordId);
			if(idx <= 0) { throw new InvalidRecordIDException("setRecord: Invalid Record ID: "+recordId); }
			byte[] temp = new byte[numBytes];
			if(numBytes != 0) { System.arraycopy(newData, offset, temp, 0, numBytes); }
			state.records.set(idx, temp);
			state.recordTags.set(idx, Integer.valueOf(tag));
			state.lastModified = System.currentTimeMillis();
			state.version++;
			saveRecordStoreRecord(recordId == 0 ? 1 : recordId);
		}

		notifyRecordChanged(recordId == 0 ? 1 : recordId);
	}


	/* ************************************************************
			RecordEnumeration implementation
	    *********************************************************** */

	private class enumeration implements RecordEnumeration
	{
		private int index;
		private int[] elements, tagsToMatch;
		private int count;
		private boolean keepUpdated;
		RecordFilter filter;
		RecordComparator comparator;

		private final RecordListener recordListener = new RecordListener() 
		{
			public void recordAdded(RecordStore recordStore, int recordId) { rebuild(); }
	
			public void recordChanged(RecordStore recordStore, int recordId) { rebuild(); }
	
			public void recordDeleted(RecordStore recordStore, int recordId) { rebuild(); }
	
		};

		public enumeration(RecordFilter filter, RecordComparator comparator, boolean keepUpdated)
		{
			this(filter, comparator, keepUpdated, null);
		}

		public enumeration(RecordFilter filter, RecordComparator comparator, boolean keepUpdated, int[] tags)
		{
			this.keepUpdated = keepUpdated;
			this.filter = filter;
			this.comparator = comparator;
			this.tagsToMatch = tags;
			rebuild();
			if (keepUpdated) { RecordStore.this.addRecordListener(recordListener); }
		}

		public void destroy() 
		{ 
			Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Enum Destroy called");
			keepUpdated(false);
			elements = new int[0];
			count = 0;
			index = 0;
		}

		public int getRecordId(int index) throws IllegalArgumentException, RecordStoreNotOpenException
		{
			checkOpen();
			if(index < 0 || index >= count) {throw new IllegalArgumentException("Cannot get Record ID, as the received index is out of bounds"); }
			return elements[index];
		}

		public boolean hasNextElement() { return count > 0 && index < count; }

		public boolean hasPreviousElement() { return index > 0 && count > 0; }

		public boolean isKeptUpdated() { return keepUpdated; }

		public void keepUpdated(boolean keepUpdated) 
		{
			if (keepUpdated) 
			{
				if (!this.keepUpdated) 
				{
					rebuild();
					RecordStore.this.addRecordListener(recordListener);
				}
			} 
			else { RecordStore.this.removeRecordListener(recordListener); }
			this.keepUpdated = keepUpdated;
		}

		public byte[] nextRecord() throws InvalidRecordIDException, RecordStoreNotOpenException
		{
			checkOpen();
			if(index >= count) { throw(new InvalidRecordIDException("Next Record ID is out of bounds")); }
			Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Enum Next Record " + index);
			return recordDataById(elements[index++]);
		}

		public int nextRecordId() throws InvalidRecordIDException, RecordStoreNotOpenException	
		{
			checkOpen();
			if(index >= count) { throw(new InvalidRecordIDException("Next Record ID is out of bounds")); }
			Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Enum Next Record ID " + elements[index]);
			return elements[index++];
		}

		public int numRecords()
		{
			Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Enum numRecords()");
			return count;
		}

		public byte[] previousRecord() throws InvalidRecordIDException, RecordStoreNotOpenException
		{
			checkOpen();
			if(index <= 0 || count == 0) { throw new InvalidRecordIDException("Previous Record is out of bounds"); }
			Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Enum Previous Record " + (index-1));
			return recordDataById(elements[--index]);
		}

		public int previousRecordId() throws InvalidRecordIDException, RecordStoreNotOpenException
		{
			checkOpen();
			if(index <= 0 || count == 0) { throw new InvalidRecordIDException("Previous Record is out of bounds"); }
			Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Enum Previous Record ID " + elements[index-1]);
			return elements[--index];
		}

		public void rebuild()
		{
			reset();
			if(state == null)
			{
				elements = new int[0];
				count = 0;
				return;
			}

			synchronized (state)
			{
				elements = new int[state.recordIds.size()];
				count = 0;
				Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": Enumerator > " + (filter == null ? "Not Filtered" : "Filtered") + " Size:" + state.records.size());

				for (int i = 1; i < state.records.size(); i++)
				{
					byte[] data = state.records.get(i);
					boolean matchesFilter = filter == null || filter.matches(data == null ? new byte[]{} : data.clone());
					// If the tags array is null, return all records; if it exists but has length zero, return an empty enumeration.
					boolean matchesTag = tagsToMatch == null || matchesTag(state.recordTags.get(i).intValue(), tagsToMatch);
					if (matchesFilter && matchesTag) { elements[count++] = state.recordIds.get(i).intValue(); }
				}

				if(comparator!=null)
				{
					Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "Comparator");
					for (int i = 0; i < count - 1; i++)
					{
						for (int j = 0; j < count - 1 - i; j++)
						{
							try
							{
								if (comparator.compare(recordDataById(elements[j]), recordDataById(elements[j + 1])) == RecordComparator.FOLLOWS)
								{
									int temp = elements[j];
									elements[j] = elements[j + 1];
									elements[j + 1] = temp;
								}
							}
							catch (Exception e) { }
						}
					}
				}
			}
		}

		private boolean matchesTag(int recordTag, int[] tags) 
		{
			for (int i = 0; i < tags.length; i++) { if (recordTag == tags[i]) { return true; } }
			return false;
		}

		public void reset() { index = 0; }
	}

	/* ************************************************************
			DoJa-specific methods
	    *********************************************************** */

	public void setScratchPadIndex(int index) { scratchPadIndex = index; }

	/* ************************************************************
			Saving to and loading from disk
	    *********************************************************** */

	private static void writeFileReplace(String path, byte[] data) throws IOException {
		File out = new File(path);
		File parent = out.getParentFile();
		if(parent != null && !parent.exists()) { parent.mkdirs(); }
		File tmp = new File(path + ".tmp");
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(tmp);
			fos.write(data);
		} finally {
			if (fos != null) {
				try { fos.close(); } catch (Exception ignore) { }
			}
		}
		Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	private String jsonEscape(String in)
	{
		if(in == null) { return ""; }
		return in.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private byte[] buildRecordStoreJsonBytes() {
		final String ownerVersion = Mobile.isDoJa ? Mobile.getPlatform().loader.getProperty("AppVer") : Mobile.getPlatform().loader.getProperty("MIDlet-Version");
		String recordName = state.name; // TODO: For doja, get the sp index

		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String lastModifiedDate = dateFormat.format(new Date(state.lastModified));

		int[] validRecords = new int[state.recordIds.size() - 1];

		// Building JSON string
		StringBuilder jsonBuilder = new StringBuilder();
		jsonBuilder.append("{\n")
			.append("  \"rmsVersion\": ").append("\"" + RMS_VERSION + "\"").append(",\n")
			.append("  \"rmsDate\": ").append("\"" + lastModifiedDate + "\"").append(",\n")
			.append("  \"ownerVersion\": \"").append(jsonEscape(ownerVersion)).append("\",\n")
			.append("  \"otherWrite\": ").append(state.writablebyothers ? 1 : 0).append(",\n")
			.append("  \"lastModified\": ").append(state.lastModified).append(",\n")
			.append("  \"modificationCount\": ").append(state.version).append(",\n")
			.append("  \"authentication\": ").append(state.authmode).append(",\n")
			.append("  \"ownerVendor\": \"").append(jsonEscape(state.vendorname)).append("\",\n")
			.append("  \"password\": \"").append(jsonEscape(state.password)).append("\",\n")
			.append("  \"recordName\": \"").append(jsonEscape(recordName)).append("\",\n")
			.append("  \"baseName\": \"").append(jsonEscape(state.basename)).append("\",\n")
			.append("  \"ownerName\": \"").append(jsonEscape(state.suitename)).append("\",\n")
			.append("  \"compatibleLastId\": ").append(state.nextid).append(",\n");

		// Write tags, followed by the record IDs
		for (int i = 1; i < state.recordIds.size(); i++) // Tags
		{
			jsonBuilder.append("  \"tag:").append(state.recordIds.get(i)).append("\": ").append(state.recordTags.get(i)).append(",\n");
			validRecords[i - 1] = state.recordIds.get(i).intValue();
		}
		jsonBuilder.append("  \"ids\": ").append(Arrays.toString(validRecords)); // IDs

		jsonBuilder.append("\n}");
		return jsonBuilder.toString().getBytes();
	}

	private void saveRecordStoreRecord(int recordId) {
		try {
			writeFileReplace(state.rmsPath + "/" + state.basename + ".rms", buildRecordStoreJsonBytes());
			int idx = indexOfRecordId(recordId);
			if (idx <= 0) { return; }
			byte[] data = state.records.get(idx);
			if (data == null) { data = new byte[]{}; }
			writeFileReplace(state.rmsPath + "/" + state.basename + "." + recordId, data);
		} catch (Exception e) {
			Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Couldn't save RecordStore record " + recordId + " in " + name + " :" + e.getMessage());
			e.printStackTrace();
		}
	}

	private void saveRecordStoreDeletedRecord(int recordId) {
		try {
			writeFileReplace(state.rmsPath + "/" + state.basename + ".rms", buildRecordStoreJsonBytes());
			try { new File(state.rmsPath + "/" + state.basename + "." + recordId).delete(); } catch (Exception ignore) { }
		} catch (Exception e) {
			Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Couldn't save RecordStore delete " + recordId + " in " + name + " :" + e.getMessage());
			e.printStackTrace();
		}
	}

	private void resetToEmptyStore() {
		state.resetVectors();
		state.version = 1;
		state.nextid = state.records.size();
		state.lastModified = System.currentTimeMillis();
	}

	public void saveRecordStore()
	{
		if(state == null) { return; }
		try
		{
			writeFileReplace(state.rmsPath + "/" + state.basename + ".rms", buildRecordStoreJsonBytes());
			for(int i = 1; i < state.recordIds.size(); i++) // Write Binary Data
			{
				if(state.records.get(i) == null) { continue; }
				writeFileReplace(state.rmsPath + "/" + state.basename + "." + state.recordIds.get(i), state.records.get(i));
			}
			deleteOutdatedRecordFiles(state.rmsPath, state.basename);
		}
		catch (Exception e)
		{
			Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Couldn't save RecordStore " + name + " :" + e.getMessage());
			e.printStackTrace(); 
		}
	}

	public void loadRecordStore(boolean createIfNecessary) throws RecordStoreException, RecordStoreNotFoundException, SecurityException
	{
		loadState(createIfNecessary);
	}

	private void loadState(boolean createIfNecessary) throws RecordStoreException, RecordStoreNotFoundException, SecurityException
	{
		state.file = new File(state.rmsFile);
		if(!state.file.exists())
		{
			Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": New recordStore file format not found, checking for legacy one...");
			File legacy = new File(state.rmsPath + "/" + state.name);
			if(legacy.exists())
			{
				Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": Legacy recordStore file found! Converting to new format...");
				loadLegacyRecordStore(legacy.getAbsolutePath(), createIfNecessary);
				return;
			}

			if(!createIfNecessary) { throw (new RecordStoreNotFoundException("Record Store Doesn't Exist: " + state.suitename + "/" + state.basename + ".rms")); }

			try
			{
				Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "> Creating New Record Store "+state.suitename+"/"+state.basename+".rms");
				resetToEmptyStore();
				saveRecordStore();
			}
			catch (Exception e)
			{
				Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + e.getMessage());
				throw(new RecordStoreException("Problem Opening Record Store (createIfNecessary "+createIfNecessary+"): "+state.rmsFile));
			}
			return;
		}
		
		try 
		{
			state.resetVectors();
			StringBuilder jsonBuilder = new StringBuilder();
			FileInputStream fis = new FileInputStream(state.rmsFile);
			Scanner scanner = new Scanner(fis);
			while (scanner.hasNextLine()) { jsonBuilder.append(scanner.nextLine().trim()); }
			scanner.close();
			fis.close();

			String jsonString = jsonBuilder.toString();
			if (jsonString == null || jsonString.length() < 2) {
				if (createIfNecessary) {
					resetToEmptyStore();
					saveRecordStore();
					return;
				}
				throw new RecordStoreException("Invalid RecordStore metadata: " + state.rmsFile);
			}
			jsonString = jsonString.substring(1, jsonString.length() - 1).trim();
			String[] entries = jsonString.split(",(?![^\\[]*\\])");

			Map<Integer, Integer> tagsById = new HashMap<Integer, Integer>();
			for (int entryIndex = 0; entryIndex < entries.length; entryIndex++)
			{
				String entry = entries[entryIndex];
				int colonIndex = entry.indexOf("\":");
				if (colonIndex != -1) 
				{
					String key = entry.substring(1, colonIndex).trim().replace("\"", "");
					String value = entry.substring(colonIndex+2).trim();

					if (value.startsWith("\"") && value.endsWith("\"")) 
					{
						if(key.equals("password")) { state.password = value.substring(1, value.length() - 1); }
					} 
					else if (value.startsWith("[") && value.endsWith("]"))
					{
						String arrayContent = value.substring(1, value.length() - 1).trim();
						if(arrayContent.length() == 0) { continue; }
						String[] arrayItems = arrayContent.split(",");
						if(key.contains("ids")) 
						{
							for(int i = 0; i < arrayItems.length; i++) { state.recordIds.add(Integer.valueOf(Integer.parseInt(arrayItems[i].trim()))); }
						}
					} 
					else 
					{ 
						if(key.indexOf("tag:") == 0) { tagsById.put(Integer.valueOf(Integer.parseInt(key.substring(4))), Integer.valueOf(Integer.parseInt(value))); }
						else if(key.equals("otherWrite")) { state.writablebyothers = (Integer.parseInt(value) == 1); }
						else if(key.equals("lastModified")) { state.lastModified = Long.parseLong(value); }
						else if(key.equals("authentication")) { state.authmode = Integer.parseInt(value); }
						else if(key.equals("modificationCount")) { state.version = Integer.parseInt(value); }
						else if(key.equals("compatibleLastId")) { state.nextid = Integer.parseInt(value); }
					}
				}
			}

			while (state.recordTags.size() < state.recordIds.size())
			{
				int rid = state.recordIds.get(state.recordTags.size()).intValue();
				Integer tag = tagsById.get(Integer.valueOf(rid));
				state.recordTags.add(tag == null ? Integer.valueOf(0) : tag);
			}

			if(!Mobile.getPlatform().loader.suitename.equals(state.suitename) && state.authmode != AUTHMODE_ANY) { throw new SecurityException("This suite does not have authorization to access the requested RecordStore:" + name); }

			for(int i = 1; i < state.recordIds.size(); i++) // Read Binary Data
			{
				FileInputStream binfis = null;
				try {
					binfis = new FileInputStream(state.rmsFile.substring(0, state.rmsFile.length()-4) + "." + state.recordIds.get(i));
					byte[] binData = new byte[binfis.available()];
					int read = binfis.read(binData);
					if(read < binData.length) { binData = Arrays.copyOf(binData, Math.max(read, 0)); }
					state.records.add(binData);
				} catch (Exception e) {
					state.records.add(new byte[]{});
				} finally {
					if (binfis != null) { try { binfis.close(); } catch (Exception ignore) { } }
				}
			}

			if(state.nextid <= 0)
			{
				int max = 0;
				for(int i = 1; i < state.recordIds.size(); i++) { if(state.recordIds.get(i).intValue() > max) { max = state.recordIds.get(i).intValue(); } }
				state.nextid = max + 1;
			}
		} 
		catch (SecurityException e)
		{
			throw e;
		}
		catch (Exception e) 
		{ 
			Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": Couldn't load recordStore:" + name + " :" + e.getMessage());
			e.printStackTrace();
			if (createIfNecessary) {
				resetToEmptyStore();
				saveRecordStore();
				return;
			}
			if (e instanceof RecordStoreException) { throw (RecordStoreException) e; }
			throw new RecordStoreException("Couldn't load recordStore: " + name);
		}
	}

	// The legacy format had no handling of access modes, authorization, etc. For original FreeJ2ME it's even worse, it didn't even save the lastModified date or any recordIDs.
	public void loadLegacyRecordStore(String filePath, boolean createIfNecessary) throws RecordStoreException, RecordStoreNotFoundException
	{
		int offset = 0;
		int reclen;

		FileInputStream fis = null;
		ByteArrayOutputStream bos = null;

		file = new File(filePath);
		try // Read Records
		{
			state.resetVectors();
			fis = new FileInputStream(file);
			bos = new ByteArrayOutputStream();
			
			byte[] buffer = new byte[1024];
			int bytesRead;
			while ((bytesRead = fis.read(buffer)) != -1) { bos.write(buffer, 0, bytesRead); }
			byte[] data = bos.toByteArray();

			if(data.length>=4)
			{
				state.version = getUInt16(data, offset); offset+=2;
				state.nextid = getUInt16(data, offset); offset+=2;
				int recordcount = getUInt16(data, offset); offset+=2;
				Mobile.log(Mobile.LOG_DEBUG, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "Record count in "+filePath + ": " + recordcount);

				for(int i=0; i<recordcount; i++)
				{
					reclen = getUInt16(data, offset);
					offset+=2;
					loadRecord(data, offset, reclen);
					offset+=reclen;
				}

				if(data.length - offset < 8)
				{
					state.lastModified = System.currentTimeMillis();
					for(int i = 0; i < recordcount; i++) 
					{
						state.recordIds.addElement(Integer.valueOf(i+1));
						state.recordTags.addElement(Integer.valueOf(0));
					}
				}
				else
				{
					state.lastModified = getLong(data, offset); offset+=8;
					if(data.length - offset >= 4)
					{
						for(int i = 0; i < recordcount; i++) 
						{
							state.recordIds.addElement(Integer.valueOf(getUint32(data, offset)));
							state.recordTags.addElement(Integer.valueOf(0));
							offset+=4;
						}
					}
					else
					{
						for(int i = 0; i < recordcount; i++) 
						{
							state.recordIds.addElement(Integer.valueOf(i+1));
							state.recordTags.addElement(Integer.valueOf(0));
						}
					}
				}
			}
			saveRecordStore();
			file.delete();
		}
		catch (Exception e)
		{
			Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + "Problem Reading Record Store: "+filePath);
			Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": " + e.getMessage());
			throw(new RecordStoreException("Problem Reading Record Store: "+filePath));
		}
		finally 
		{
			try 
			{
				if (fis != null) { fis.close(); }
				if (bos != null) { bos.close(); }
			} 
			catch (IOException e) { e.printStackTrace(); }
		}
	}


	private static String returnRecordStoreName(String filePath) throws RecordStoreException, RecordStoreNotFoundException, SecurityException
	{
		try 
		{
			StringBuilder jsonBuilder = new StringBuilder();
			FileInputStream fis = new FileInputStream(filePath);
			Scanner scanner = new Scanner(fis);
			while (scanner.hasNextLine()) { jsonBuilder.append(scanner.nextLine().trim()); }
			scanner.close();
			fis.close();

			String jsonString = jsonBuilder.toString();
			jsonString = jsonString.substring(1, jsonString.length() - 1).trim();
			String[] entries = jsonString.split(",(?![^\\[]*\\])");
			for (int i = 0; i < entries.length; i++)
			{
				String entry = entries[i];
				int colonIndex = entry.indexOf("\":");
				if (colonIndex != -1) 
				{
					String key = entry.substring(1, colonIndex).trim().replace("\"", "");
					String value = entry.substring(colonIndex+2).trim();
					if(key.equals("recordName")) { return value.substring(1, value.length() - 1).trim(); }
				}
			}
		}
		catch(Exception e) { Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": Couldn't return the record Store Name:" + e.getMessage()); }
		
		Mobile.log(Mobile.LOG_WARNING, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": Record does not have a recordName field. Expect bugs!");
		return null;
	}

	public final void deleteOutdatedRecords(String rmsPath, String basename) 
	{
		deleteOutdatedRecordFiles(rmsPath, basename);
	}

	private void deleteOutdatedRecordFiles(String rmsPath, String basename)
	{
		File directory = new File(rmsPath);
		if (directory.exists() && directory.isDirectory()) 
		{
			File[] files = directory.listFiles();
			if (files != null) 
			{
				for (int i = 0; i < files.length; i++)
				{
					File f = files[i];
					String fileName = f.getName();
					if (!f.isFile() || !fileName.startsWith(basename + ".") || fileName.equals(basename + ".rms")) { continue; }
					String suffix = fileName.substring((basename + ".").length());
					try
					{
						int id = Integer.parseInt(suffix);
						if(state == null || !state.recordIds.contains(Integer.valueOf(id))) { f.delete(); }
					}
					catch (Exception ignore) { }
				}
			}
		} 
	}

	private void notifyRecordAdded(int recordId)
	{
		notifyRecordEvent(recordId, 0);
	}

	private void notifyRecordChanged(int recordId)
	{
		notifyRecordEvent(recordId, 1);
	}

	private void notifyRecordDeleted(int recordId)
	{
		notifyRecordEvent(recordId, 2);
	}

	private void notifyRecordEvent(int recordId, int type)
	{
		Vector<RecordStore> handles;
		synchronized (STORE_LOCK)
		{
			if(state == null) { return; }
			handles = new Vector<RecordStore>(state.openHandles);
		}

		for(int h = 0; h < handles.size(); h++)
		{
			RecordStore rs = handles.get(h);
			Vector<RecordListener> copy = new Vector<RecordListener>(rs.listeners);
			for(int i=0; i<copy.size(); i++)
			{
				RecordListener l = copy.get(i);
				if(type == 0) { l.recordAdded(rs, recordId); }
				else if(type == 1) { l.recordChanged(rs, recordId); }
				else { l.recordDeleted(rs, recordId); }
			}
		}
	}

	// These two are used so that FreeJ2ME-Plus matches SquirrelJME's save layout
	public static final String generateBaseName(String owner, String name) 
	{
		return generateBaseName(owner, Mobile.getPlatform().loader.suitename, name);
	}

	private static final String generateBaseName(String owner, String suite, String name)
	{
        String base64Encoded = "";
		try 
		{
			base64Encoded = Base64Util.encode(name.getBytes("UTF-8"))
				.toLowerCase()
				.replace('=', '_');
		} 
		catch (Exception e) { Mobile.log(Mobile.LOG_ERROR, RecordStore.class.getPackage().getName() + "." + RecordStore.class.getSimpleName() + ": Failed to properly encode the recordStores disk name!"); }
		return String.format("%08x%02d%s", ownerHashcode(owner, suite), name.length(), base64Encoded);
    }

	public static final int ownerHashcode(String owner, String name) { return name.hashCode() ^ owner.hashCode(); }
}
