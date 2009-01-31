package freenet.client.async;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.db4o.ObjectContainer;

import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;
import freenet.support.io.NativeThread;

public class SimpleManifestPutter extends BaseClientPutter implements PutCompletionCallback {
	// Only implements PutCompletionCallback for the final metadata insert

	private class PutHandler extends BaseClientPutter implements PutCompletionCallback {
		
		protected PutHandler(final SimpleManifestPutter smp, String name, Bucket data, ClientMetadata cm, boolean getCHKOnly) {
			super(smp.priorityClass, smp.client);
			this.persistent = SimpleManifestPutter.this.persistent();
			this.cm = cm;
			this.data = data;
			InsertBlock block = 
				new InsertBlock(data, cm, FreenetURI.EMPTY_CHK_URI);
			this.origSFI =
				new SingleFileInserter(this, this, block, false, ctx, false, getCHKOnly, true, null, null, true, null, earlyEncode);
			metadata = null;
		}

		protected PutHandler(final SimpleManifestPutter smp, String name, FreenetURI target, ClientMetadata cm) {
			super(smp.getPriorityClass(), smp.client);
			this.persistent = SimpleManifestPutter.this.persistent();
			this.cm = cm;
			this.data = null;
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, target, cm);
			metadata = m;
			origSFI = null;
		}
		
		protected PutHandler(final SimpleManifestPutter smp, String name, String targetInArchive, ClientMetadata cm, Bucket data) {
			super(smp.getPriorityClass(), smp.client);
			this.persistent = SimpleManifestPutter.this.persistent();
			this.cm = cm;
			this.data = data;
			this.targetInArchive = targetInArchive;
			Metadata m = new Metadata(Metadata.ARCHIVE_INTERNAL_REDIRECT, null, null, targetInArchive, cm);
			metadata = m;
			origSFI = null;
		}
		
		private SingleFileInserter origSFI;
		private ClientMetadata cm;
		private Metadata metadata;
		private String targetInArchive;
		private final Bucket data;
		private final boolean persistent;
		
		public void start(ObjectContainer container, ClientContext context) throws InsertException {
			if (origSFI == null) {
				 Logger.error(this, "origSFI is null on start(), should be impossible", new Exception("debug"));
				 return;
			}
			if (metadata != null) {
				Logger.error(this, "metdata=" + metadata + " on start(), should be impossible", new Exception("debug"));
				return;
			}
			if(persistent)
				container.activate(origSFI, 1);
			origSFI.start(null, container, context);
			if(persistent())
				container.deactivate(origSFI, 1);
			origSFI = null;
			if(persistent)
				container.store(this);
		}
		
		public void cancel(ObjectContainer container, ClientContext context) {
			super.cancel();
		}
		
		@Override
		public FreenetURI getURI() {
			return null;
		}

		@Override
		public boolean isFinished() {
			return SimpleManifestPutter.this.finished || cancelled || SimpleManifestPutter.this.cancelled;
		}

		public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Completed "+this);
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
				container.activate(runningPutHandlers, 2);
			}
			SimpleManifestPutter.this.onFetchable(this, container);
			synchronized(SimpleManifestPutter.this) {
				runningPutHandlers.remove(this);
				if(persistent)
					container.store(runningPutHandlers);
				if(!runningPutHandlers.isEmpty()) {
					if(logMINOR) {
						Logger.minor(this, "Running put handlers: "+runningPutHandlers.size());
//						for(Object o : runningPutHandlers) {
//							boolean activated = true;
//							if(persistent) {
//								activated = container.ext().isActive(o);
//								if(!activated) container.activate(o, 1);
//							}
//							Logger.minor(this, "Still running: "+o);
//							if(!activated)
//								container.deactivate(o, 1);
//						}
					}
					return;
				}
			}
			insertedAllFiles(container);
			if(persistent) {
				container.deactivate(runningPutHandlers, 1);
				container.deactivate(SimpleManifestPutter.this, 1);
			}
		}

		public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Failed: "+this+" - "+e, e);
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			fail(e, container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "onEncode("+key+") for "+this);
			if(metadata == null) {
				// The file was too small to have its own metadata, we get this instead.
				// So we make the key into metadata.
				if(persistent) {
					container.activate(key, 5);
					container.activate(SimpleManifestPutter.this, 1);
				}
				Metadata m =
					new Metadata(Metadata.SIMPLE_REDIRECT, null, null, key.getURI(), cm);
				onMetadata(m, null, container, context);
				if(persistent) {
					container.deactivate(SimpleManifestPutter.this, 1);
				}
			}
		}

		public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {}

		public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Assigning metadata: "+m+" for "+this+" from "+state+" persistent="+persistent,
					new Exception("debug"));
			if(metadata != null) {
				Logger.error(this, "Reassigning metadata", new Exception("debug"));
				return;
			}
			metadata = m;
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
				container.activate(putHandlersWaitingForMetadata, 2);
			}
			
			boolean allMetadatas = false;
			
			synchronized(SimpleManifestPutter.this) {
				putHandlersWaitingForMetadata.remove(this);
				if(persistent) {
					container.store(putHandlersWaitingForMetadata);
					container.store(this);
				}
				allMetadatas = putHandlersWaitingForMetadata.isEmpty();
				if(!allMetadatas) {
					if(logMINOR)
						Logger.minor(this, "Still waiting for metadata: "+putHandlersWaitingForMetadata.size());
				}
			}
			if(allMetadatas) {
				// Will resolve etc.
				gotAllMetadata(container, context);
			} else {
				// Resolve now to speed up the insert.
				try {
					byte[] buf = m.writeToByteArray();
					if(buf.length > Metadata.MAX_SIZE_IN_MANIFEST)
						throw new MetadataUnresolvedException(new Metadata[] { m }, "Too big");
				} catch (MetadataUnresolvedException e) {
					try {
						resolve(e, container, context);
					} catch (IOException e1) {
						fail(new InsertException(InsertException.BUCKET_ERROR, e1, null), container);
						return;
					} catch (InsertException e1) {
						fail(e1, container);
					}
				}
			}
			if(persistent) {
				container.deactivate(putHandlersWaitingForMetadata, 1);
				container.deactivate(SimpleManifestPutter.this, 1);
			}
		}

		@Override
		public void addBlock(ObjectContainer container) {
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
			}
			SimpleManifestPutter.this.addBlock(container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}
		
		@Override
		public void addBlocks(int num, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.addBlocks(num, container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}
		
		@Override
		public void completedBlock(boolean dontNotify, ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.completedBlock(dontNotify, container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}
		
		@Override
		public void failedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.failedBlock(container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}
		
		@Override
		public void fatallyFailedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.fatallyFailedBlock(container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}
		
		@Override
		public void addMustSucceedBlocks(int blocks, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.addMustSucceedBlocks(blocks, container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}
		
		@Override
		public void notifyClients(ObjectContainer container, ClientContext context) {
			// FIXME generate per-filename events???
		}

		public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
				container.activate(waitingForBlockSets, 2);
			}
			boolean allBlockSets = false;
			synchronized(SimpleManifestPutter.this) {
				waitingForBlockSets.remove(this);
				if(persistent)
					container.store(waitingForBlockSets);
				allBlockSets = waitingForBlockSets.isEmpty();
			}
			if(allBlockSets)
				SimpleManifestPutter.this.blockSetFinalized(container, context);
			if(persistent) {
				container.deactivate(waitingForBlockSets, 1);
				container.deactivate(SimpleManifestPutter.this, 1);
			}
		}

		@Override
		public void onMajorProgress(ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.onMajorProgress(container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		public void onFetchable(ClientPutState state, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.onFetchable(this, container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		@Override
		public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
			// Ignore
		}

	}

	static boolean logMINOR;
	private final HashMap<String,Object> putHandlersByName;
	private final HashSet<PutHandler> runningPutHandlers;
	private final HashSet<PutHandler> putHandlersWaitingForMetadata;
	private final HashSet<PutHandler> waitingForBlockSets;
	private final HashSet<PutHandler> putHandlersWaitingForFetchable;
	private FreenetURI finalURI;
	private FreenetURI targetURI;
	private boolean finished;
	private final InsertContext ctx;
	final ClientCallback cb;
	private final boolean getCHKOnly;
	private boolean insertedAllFiles;
	private boolean insertedManifest;
	private final HashMap<Metadata,SingleFileInserter> metadataPuttersByMetadata;
	private final HashMap<Metadata,SingleFileInserter> metadataPuttersUnfetchable;
	private final String defaultName;
	private int numberOfFiles;
	private long totalSize;
	private boolean metadataBlockSetFinalized;
	private Metadata baseMetadata;
	private boolean hasResolvedBase;
	private final static String[] defaultDefaultNames =
		new String[] { "index.html", "index.htm", "default.html", "default.htm" };
	private int bytesOnZip;
	private LinkedList<PutHandler> elementsToPutInArchive;
	private boolean fetchable;
	private final boolean earlyEncode;
	
	public SimpleManifestPutter(ClientCallback cb, 
			HashMap manifestElements, short prioClass, FreenetURI target, 
			String defaultName, InsertContext ctx, boolean getCHKOnly, RequestClient clientContext, boolean earlyEncode) {
		super(prioClass, clientContext);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.defaultName = defaultName;
		this.targetURI = target;
		this.cb = cb;
		this.ctx = ctx;
		this.getCHKOnly = getCHKOnly;
		this.earlyEncode = earlyEncode;
		putHandlersByName = new HashMap<String,Object>();
		runningPutHandlers = new HashSet<PutHandler>();
		putHandlersWaitingForMetadata = new HashSet<PutHandler>();
		putHandlersWaitingForFetchable = new HashSet<PutHandler>();
		waitingForBlockSets = new HashSet<PutHandler>();
		metadataPuttersByMetadata = new HashMap<Metadata,SingleFileInserter>();
		metadataPuttersUnfetchable = new HashMap<Metadata,SingleFileInserter>();
		elementsToPutInArchive = new LinkedList<PutHandler>();
		makePutHandlers(manifestElements, putHandlersByName);
		checkZips();
	}

	private void checkZips() {
		// If there are too few files in the zip, then insert them directly instead.
		// FIXME do something.
	}

	public void start(ObjectContainer container, ClientContext context) throws InsertException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if (logMINOR)
			Logger.minor(this, "Starting " + this+" persistence="+persistent());
		PutHandler[] running;

		if(persistent()) {
			container.activate(runningPutHandlers, 2);
		}
		synchronized (this) {
			running = (PutHandler[]) runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
		}
		try {
			boolean persistent = persistent(); // this might get deactivated ...
			for (int i = 0; i < running.length; i++) {
				running[i].start(container, context);
				if(persistent && !container.ext().isActive(this))
					container.activate(this, 1);
				if (logMINOR)
					Logger.minor(this, "Started " + i + " of " + running.length);
				if (isFinished()) {
					if (logMINOR)
						Logger.minor(this, "Already finished, killing start() on " + this);
					return;
				}
			}
			if (logMINOR)
				Logger.minor(this, "Started " + running.length + " PutHandler's for " + this);
			if (running.length == 0) {
				insertedAllFiles = true;
				if(persistent())
					container.store(this);
				gotAllMetadata(container, context);
			}
		} catch (InsertException e) {
			cancelAndFinish(container);
			throw e;
		}
	}
	
	private void makePutHandlers(HashMap manifestElements, HashMap<String,Object> putHandlersByName) {
		makePutHandlers(manifestElements, putHandlersByName, "/");
	}
	
	private void makePutHandlers(HashMap manifestElements, HashMap<String,Object> putHandlersByName, String ZipPrefix) {
		Iterator it = manifestElements.keySet().iterator();
		while(it.hasNext()) {
			String name = (String) it.next();
			Object o = manifestElements.get(name);
			if(o instanceof HashMap) {
				HashMap<String,Object> subMap = new HashMap<String,Object>();
				putHandlersByName.put(name, subMap);
				makePutHandlers((HashMap)o, subMap, ZipPrefix+name+ '/');
				if(Logger.shouldLog(Logger.DEBUG, this))
					Logger.debug(this, "Sub map for "+name+" : "+subMap.size()+" elements from "+((HashMap)o).size());
			} else {
				ManifestElement element = (ManifestElement) o;
				String mimeType = element.mimeOverride;
				if(mimeType == null)
					mimeType = DefaultMIMETypes.guessMIMEType(name, true);
				ClientMetadata cm;
				if(mimeType == null || mimeType.equals(DefaultMIMETypes.DEFAULT_MIME_TYPE))
					cm = null;
				else
					cm = new ClientMetadata(mimeType);
				PutHandler ph;
				Bucket data = element.data;
				if(element.targetURI != null) {
					ph = new PutHandler(this, name, element.targetURI, cm);
					// Just a placeholder, don't actually run it
				} else {
					// Decide whether to put it in the ZIP.
					// FIXME support multiple ZIPs and size limits.
					// FIXME support better heuristics.
					// ZIP is slightly more compact, can use this formula:
					//int sz = (int)data.size() + 40 + element.fullName.length();
					// TAR is less compact (but with a chained compressor is far more efficient due to inter-file compression):
					int sz = 512 + (((((int)data.size()) + 511) / 512) * 512);
					if((data.size() <= 65536) &&
							// tar pads to next 10k, and has 1k end-headers
							// FIXME this also needs to include the metadata!!
							// FIXME no way to know at this point how big that is...
							// need major refactoring to make this work... for now just
							// assume 64k will cover it.
							(bytesOnZip + sz < (2038-64)*1024)) { // totally dumb heuristic!
						bytesOnZip += sz;
						// Put it in the zip.
						if(logMINOR)
							Logger.minor(this, "Putting into ZIP: "+name);
						ph = new PutHandler(this, name, ZipPrefix+element.fullName, cm, data);
						if(logMINOR)
							Logger.minor(this, "Putting file into container: "+element.fullName+" : "+ph);
						elementsToPutInArchive.addLast(ph);
						numberOfFiles++;
						totalSize += data.size();
					} else {
							ph = new PutHandler(this,name, data, cm, getCHKOnly);
						runningPutHandlers.add(ph);
						putHandlersWaitingForMetadata.add(ph);
						putHandlersWaitingForFetchable.add(ph);
						if(logMINOR)
							Logger.minor(this, "Inserting separately as PutHandler: "+name+" : "+ph+" persistent="+ph.persistent()+":"+ph.persistent+" "+persistent());
						numberOfFiles++;
						totalSize += data.size();
					}
				}
				putHandlersByName.put(name, ph);
			}
		}
	}

	@Override
	public FreenetURI getURI() {
		return finalURI;
	}

	@Override
	public synchronized boolean isFinished() {
		return finished || cancelled;
	}

	private final DBJob runGotAllMetadata = new DBJob() {

		public void run(ObjectContainer container, ClientContext context) {
			context.jobRunner.removeRestartJob(this, NativeThread.NORM_PRIORITY, container);
			container.activate(SimpleManifestPutter.this, 1);
			innerGotAllMetadata(container, context);
			container.deactivate(SimpleManifestPutter.this, 1);
		}
		
	};
	
	/**
	 * Called when we have metadata for all the PutHandler's.
	 * This does *not* necessarily mean we can immediately insert the final metadata, since
	 * if these metadata's are too big, they will need to be inserted separately. See
	 * resolveAndStartBase().
	 * @param container
	 * @param context
	 */
	private void gotAllMetadata(ObjectContainer container, ClientContext context) {
		// This can be huge! Run it on its own transaction to minimize the build up of stuff to commit
		// and maximise the opportunities for garbage collection.
		if(persistent()) {
			container.activate(runGotAllMetadata, 1); // need to activate .this!
			context.jobRunner.queueRestartJob(runGotAllMetadata, NativeThread.NORM_PRIORITY, container, false);
			context.jobRunner.queue(runGotAllMetadata, NativeThread.NORM_PRIORITY, false);
		} else {
			innerGotAllMetadata(null, context);
		}
	}
	
	/**
	 * Generate the global metadata, and then call resolveAndStartBase.
	 * @param container
	 * @param context
	 */
	private void innerGotAllMetadata(ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(putHandlersByName, 2); // depth 2 to load elements
		}
		if(logMINOR) Logger.minor(this, "Got all metadata");
		HashMap<String, Object> namesToByteArrays = new HashMap<String, Object>();
		namesToByteArrays(putHandlersByName, namesToByteArrays, container);
		if(defaultName != null) {
			Metadata meta = (Metadata) namesToByteArrays.get(defaultName);
			if(meta == null) {
				fail(new InsertException(InsertException.INVALID_URI, "Default name "+defaultName+" does not exist", null), container);
				return;
			}
			namesToByteArrays.put("", meta);
		} else {
			for(int j=0;j<defaultDefaultNames.length;j++) {
				String name = defaultDefaultNames[j];
				Metadata meta = (Metadata) namesToByteArrays.get(name);
				if(meta != null) {
					namesToByteArrays.put("", meta);
					break;
				}
			}
		}
		baseMetadata =
			Metadata.mkRedirectionManifestWithMetadata(namesToByteArrays);
		if(persistent()) {
			container.store(baseMetadata);
			container.store(this);
		}
		resolveAndStartBase(container, context);
		if(persistent()) {
			container.deactivate(putHandlersByName, 1);
		}
		
	}
	
	/**
	 * Attempt to insert the base metadata and the container. If the base metadata cannot be resolved,
	 * try to resolve it: start inserts for each part that cannot be resolved, and wait for them to generate
	 * URIs that can be incorporated into the metadata. This method will then be called again, and will 
	 * complete, or do more resolutions as necessary.
	 * @param container
	 * @param context
	 */
	private void resolveAndStartBase(ObjectContainer container, ClientContext context) {
		Bucket bucket = null;
		synchronized(this) {
			if(hasResolvedBase) return;
		}
		while(true) {
			try {
				if(persistent())
					container.activate(baseMetadata, 100);
				bucket = BucketTools.makeImmutableBucket(context.getBucketFactory(persistent()), baseMetadata.writeToByteArray());
				if(logMINOR)
					Logger.minor(this, "Metadata bucket is "+bucket.size()+" bytes long");
				break;
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container);
				return;
			} catch (MetadataUnresolvedException e) {
				try {
					// Start the insert for the sub-Metadata.
					// Eventually it will generate a URI and call onEncode(), which will call back here.
					if(logMINOR) Logger.minor(this, "Main metadata needs resolving: "+e);
					resolve(e, container, context);
					if(persistent())
						container.deactivate(baseMetadata, 1);
					return;
				} catch (IOException e1) {
					if(persistent())
						container.deactivate(baseMetadata, 1);
					fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container);
					return;
				} catch (InsertException e2) {
					if(persistent())
						container.deactivate(baseMetadata, 1);
					fail(e2, container);
					return;
				}
			}
		}
		if(bucket == null) return;
		synchronized(this) {
			if(hasResolvedBase) return;
			hasResolvedBase = true;
		}
		if(persistent()) {
			container.store(this);
			container.activate(elementsToPutInArchive, 2);
		}
		InsertBlock block;
		boolean isMetadata = true;
		boolean insertAsArchiveManifest = false;
		ARCHIVE_TYPE archiveType = null;
		if(!(elementsToPutInArchive.isEmpty())) {
			// There is an archive to insert.
			// We want to include the metadata.
			// We have the metadata, fortunately enough, because everything has been resolve()d.
			// So all we need to do is create the actual archive.
			try {				
				Bucket outputBucket = context.getBucketFactory(persistent()).makeBucket(baseMetadata.dataLength());
				// TODO: try both ? - maybe not worth it
				archiveType = ARCHIVE_TYPE.getDefault();
				String mimeType = (archiveType == ARCHIVE_TYPE.TAR ?
					createTarBucket(bucket, outputBucket) :
					createZipBucket(bucket, outputBucket));
				bucket.free();
				if(persistent()) bucket.removeFrom(container);
				
				if(logMINOR) Logger.minor(this, "We are using "+archiveType);
				
				// Now we have to insert the Archive we have generated.
				
				// Can we just insert it, and not bother with a redirect to it?
				// Thereby exploiting implicit manifest support, which will pick up on .metadata??
				// We ought to be able to !!
				block = new InsertBlock(outputBucket, new ClientMetadata(mimeType), targetURI);
				isMetadata = false;
				insertAsArchiveManifest = true;
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container);
				if(persistent())
					container.deactivate(baseMetadata, 1);
				return;
			}
		} else
			block = new InsertBlock(bucket, null, targetURI);
		SingleFileInserter metadataInserter;
		try {
			metadataInserter = 
				new SingleFileInserter(this, this, block, isMetadata, ctx, (archiveType == ARCHIVE_TYPE.ZIP) , getCHKOnly, false, baseMetadata, archiveType, true, null, earlyEncode);
			if(logMINOR) Logger.minor(this, "Inserting main metadata: "+metadataInserter);
			if(persistent()) {
				container.activate(metadataPuttersByMetadata, 2);
				container.activate(metadataPuttersUnfetchable, 2);
			}
			this.metadataPuttersByMetadata.put(baseMetadata, metadataInserter);
			metadataPuttersUnfetchable.put(baseMetadata, metadataInserter);
			if(persistent()) {
				container.store(metadataPuttersByMetadata);
				container.store(metadataPuttersUnfetchable);
				container.deactivate(metadataPuttersByMetadata, 1);
				container.deactivate(metadataPuttersUnfetchable, 1);
				container.deactivate(baseMetadata, 1);

			}
			metadataInserter.start(null, container, context);
		} catch (InsertException e) {
			fail(e, container);
			return;
		}
		if(persistent()) {
			container.deactivate(metadataInserter, 1);
			container.deactivate(elementsToPutInArchive, 1);
		}
	}

	private String createTarBucket(Bucket inputBucket, Bucket outputBucket) throws IOException {
		if(logMINOR) Logger.minor(this, "Create a TAR Bucket");
		
		OutputStream os = new BufferedOutputStream(outputBucket.getOutputStream());
		TarOutputStream tarOS = new TarOutputStream(os);
		tarOS.setLongFileMode(TarOutputStream.LONGFILE_GNU);
		TarEntry ze;

		for(PutHandler ph : elementsToPutInArchive) {
			if(logMINOR)
				Logger.minor(this, "Putting into tar: "+ph+" data length "+ph.data.size()+" name "+ph.targetInArchive);
			ze = new TarEntry(ph.targetInArchive);
			ze.setModTime(0);
			long size = ph.data.size();
			ze.setSize(size);
			tarOS.putNextEntry(ze);
			BucketTools.copyTo(ph.data, tarOS, size);
			tarOS.closeEntry();
		}

		// Add .metadata - after the rest.
		if(logMINOR)
			Logger.minor(this, "Putting metadata into tar: length is "+inputBucket.size());
		ze = new TarEntry(".metadata");
		ze.setModTime(0); // -1 = now, 0 = 1970.
		long size = inputBucket.size();
		ze.setSize(size);
		tarOS.putNextEntry(ze);
		BucketTools.copyTo(inputBucket, tarOS, size);

		tarOS.closeEntry();
		// Both finish() and close() are necessary.
		tarOS.finish();
		tarOS.flush();
		tarOS.close();
		
		if(logMINOR)
			Logger.minor(this, "Archive size is "+outputBucket.size());
		
		return ARCHIVE_TYPE.TAR.mimeTypes[0];
	}
	
	private String createZipBucket(Bucket inputBucket, Bucket outputBucket) throws IOException {
		if(logMINOR) Logger.minor(this, "Create a ZIP Bucket");
		
		OutputStream os = new BufferedOutputStream(outputBucket.getOutputStream());
		ZipOutputStream zos = new ZipOutputStream(os);
		ZipEntry ze;

		for(Iterator i = elementsToPutInArchive.iterator(); i.hasNext();) {
			PutHandler ph = (PutHandler) i.next();
			ze = new ZipEntry(ph.targetInArchive);
			ze.setTime(0);
			zos.putNextEntry(ze);
			BucketTools.copyTo(ph.data, zos, ph.data.size());
			zos.closeEntry();
		}

		// Add .metadata - after the rest.
		ze = new ZipEntry(".metadata");
		ze.setTime(0); // -1 = now, 0 = 1970.
		zos.putNextEntry(ze);
		BucketTools.copyTo(inputBucket, zos, inputBucket.size());

		zos.closeEntry();
		// Both finish() and close() are necessary.
		zos.finish();
		zos.flush();
		zos.close();
		
		return ARCHIVE_TYPE.ZIP.mimeTypes[0];
	}

	/**
	 * Start inserts for unresolved (too big) Metadata's. Eventually these will call back with an onEncode(),
	 * meaning they have the CHK, and we can progress to resolveAndStartBase(). 
	 * @param e
	 * @param container
	 * @param context
	 * @return
	 * @throws InsertException
	 * @throws IOException
	 */
	private void resolve(MetadataUnresolvedException e, ObjectContainer container, ClientContext context) throws InsertException, IOException {
		Metadata[] metas = e.mustResolve;
		if(persistent())
			container.activate(metadataPuttersByMetadata, 2);
		for(int i=0;i<metas.length;i++) {
			Metadata m = metas[i];
			if(persistent()) container.activate(m, 100);
			synchronized(this) {
				if(metadataPuttersByMetadata.containsKey(m)) {
					if(logMINOR) Logger.minor(this, "Already started insert for "+m+" in resolve() for "+metas.length+" Metadata's");
					continue;
				}
			}
			if(m.isResolved()) {
				Logger.error(this, "Already resolved: "+m+" in resolve() - race condition???");
				if(persistent()) container.deactivate(m, 1);
				continue;
			}
			try {
				Bucket b = m.toBucket(context.getBucketFactory(persistent()));
				
				InsertBlock ib = new InsertBlock(b, null, FreenetURI.EMPTY_CHK_URI);
				SingleFileInserter metadataInserter = 
					new SingleFileInserter(this, this, ib, true, ctx, false, getCHKOnly, false, m, null, true, null, earlyEncode);
				if(logMINOR) Logger.minor(this, "Inserting subsidiary metadata: "+metadataInserter+" for "+m);
				synchronized(this) {
					this.metadataPuttersByMetadata.put(m, metadataInserter);
				}
				metadataInserter.start(null, container, context);
				if(persistent()) {
					container.deactivate(metadataInserter, 1);
					container.deactivate(m, 1);
				}
			} catch (MetadataUnresolvedException e1) {
				resolve(e1, container, context);
				container.deactivate(m, 1);
			}
		}
		if(persistent()) {
			container.store(metadataPuttersByMetadata);
			container.deactivate(metadataPuttersByMetadata, 1);
		}
	}

	private void namesToByteArrays(HashMap putHandlersByName, HashMap<String,Object> namesToByteArrays, ObjectContainer container) {
		Iterator i = putHandlersByName.keySet().iterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			Object o = putHandlersByName.get(name);
			if(o instanceof PutHandler) {
				PutHandler ph = (PutHandler) o;
				if(persistent())
					container.activate(ph, 1);
				Metadata meta = ph.metadata;
				if(ph.metadata == null)
					Logger.error(this, "Metadata for "+name+" : "+ph+" is null");
				if(persistent())
					container.activate(meta, 100);
				Logger.minor(this, "Putting "+name);
				namesToByteArrays.put(name, meta);
				if(logMINOR)
					Logger.minor(this, "Putting PutHandler into base metadata: "+ph+" name "+name);
			} else if(o instanceof HashMap) {
				HashMap<String,Object> subMap = new HashMap<String,Object>();
				if(persistent())
					container.activate(o, 2); // Depth 1 doesn't load the elements...
				namesToByteArrays.put(name, subMap);
				if(logMINOR)
					Logger.minor(this, "Putting hashmap into base metadata: "+name+" size "+((HashMap)o).size()+" active = "+container == null ? "null" : Boolean.toString(container.ext().isActive(o)));
				Logger.minor(this, "Putting directory: "+name);
				namesToByteArrays((HashMap)o, subMap, container);
			} else
				throw new IllegalStateException();
		}
	}

	private void insertedAllFiles(ObjectContainer container) {
		if(logMINOR) Logger.minor(this, "Inserted all files");
		synchronized(this) {
			insertedAllFiles = true;
			if(finished || cancelled) {
				if(logMINOR) Logger.minor(this, "Already "+(finished?"finished":"cancelled"));
				if(persistent())
					container.store(this);
				return;
			}
			if(!insertedManifest) {
				if(logMINOR) Logger.minor(this, "Haven't inserted manifest");
				if(persistent())
					container.store(this);
				return;
			}
			finished = true;
		}
		if(persistent())
			container.store(this);
		complete(container);
	}
	
	private void complete(ObjectContainer container) {
		boolean deactivateCB = false;
		if(persistent()) {
			deactivateCB = !container.ext().isActive(cb);
			container.activate(cb, 1);
		}
		cb.onSuccess(this, container);
		if(deactivateCB)
			container.deactivate(cb, 1);
	}

	private void fail(InsertException e, ObjectContainer container) {
		// Cancel all, then call the callback
		cancelAndFinish(container);
		
		if(persistent())
			container.activate(cb, 1);
		cb.onFailure(e, this, container);
	}
	
	/**
	 * Cancel all running inserters and set finished to true.
	 */
	private void cancelAndFinish(ObjectContainer container) {
		PutHandler[] running;
		if(persistent())
			container.activate(runningPutHandlers, 2);
		synchronized(this) {
			if(finished) return;
			running = (PutHandler[]) runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
			finished = true;
		}
		if(persistent())
			container.store(this);
		
		for(int i=0;i<running.length;i++) {
			running[i].cancel();
		}
	}
	
	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		super.cancel();
		if(persistent())
			container.store(this);
		fail(new InsertException(InsertException.CANCELLED), container);
	}
	
	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(persistent()) {
			container.activate(metadataPuttersByMetadata, 2);
		}
		synchronized(this) {
			metadataPuttersByMetadata.remove(state.getToken());
			if(!metadataPuttersByMetadata.isEmpty()) {
				if(logMINOR) Logger.minor(this, "Still running metadata putters: "+metadataPuttersByMetadata.size());
				return;
			}
			Logger.minor(this, "Inserted manifest successfully on "+this);
			insertedManifest = true;
			if(finished) {
				if(logMINOR) Logger.minor(this, "Already finished");
				if(persistent())
					container.store(this);
				return;
			}
			if(!insertedAllFiles) {
				if(logMINOR) Logger.minor(this, "Not inserted all files");
				if(persistent())
					container.store(this);
				return;
			}
			finished = true;
		}
		if(persistent()) {
			container.store(metadataPuttersByMetadata);
			container.store(this);
			container.deactivate(metadataPuttersByMetadata, 1);
		}
		complete(container);
	}
	
	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		fail(e, container);
	}
	
	public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(state.getToken() == baseMetadata) {
			this.finalURI = key.getURI();
			if(logMINOR) Logger.minor(this, "Got metadata key: "+finalURI);
			if(persistent())
				container.activate(cb, 1);
			cb.onGeneratedURI(finalURI, this, container);
			if(persistent())
				container.deactivate(cb, 1);
			if(persistent())
				container.store(this);
		} else {
			// It's a sub-Metadata
			Metadata m = (Metadata) state.getToken();
			if(persistent())
				container.activate(m, 2);
			m.resolve(key.getURI());
			if(persistent())
				container.store(m);
			if(logMINOR) Logger.minor(this, "Resolved "+m+" : "+key.getURI());
			resolveAndStartBase(container, context);
		}
	}
	
	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		synchronized(this) {
			if(logMINOR) Logger.minor(this, "Transition: "+oldState+" -> "+newState);
		}
	}
	
	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(ctx, 1);
			container.activate(ctx.eventProducer, 1);
		}
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.blockSetFinalized), container, context);
	}

	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			this.metadataBlockSetFinalized = true;
			if(persistent())
				container.activate(waitingForBlockSets, 2);
			if(!waitingForBlockSets.isEmpty()) {
				if(persistent()) {
					container.store(this);
					container.deactivate(waitingForBlockSets, 1);
				}
				return;
			}
		}
		this.blockSetFinalized(container, context);
		if(persistent()) {
			container.store(this);
			container.deactivate(waitingForBlockSets, 1);
		}
	}

	@Override
	public void blockSetFinalized(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(!metadataBlockSetFinalized) return;
			if(persistent())
				container.activate(waitingForBlockSets, 2);
			if(waitingForBlockSets.isEmpty()) {
				if(persistent())
					container.deactivate(waitingForBlockSets, 1);
				return;
			}
		}
		if(persistent())
			container.deactivate(waitingForBlockSets, 1);
		super.blockSetFinalized(container, context);
		if(persistent())
			container.store(this);
	}
	
	/**
	 * Convert a HashMap of name -> bucket to a HashSet of ManifestEntry's.
	 * All are to have mimeOverride=null, i.e. we use the auto-detected mime type
	 * from the filename.
	 */
	public static HashMap bucketsByNameToManifestEntries(HashMap<String,Object> bucketsByName) {
		HashMap<String,Object> manifestEntries = new HashMap<String,Object>();
		Iterator i = bucketsByName.keySet().iterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			Object o = bucketsByName.get(name);
			if(o instanceof Bucket) {
				Bucket data = (Bucket) bucketsByName.get(name);
				manifestEntries.put(name, new ManifestElement(name, data, null,data.size()));
			} else if(o instanceof HashMap) {
				manifestEntries.put(name, bucketsByNameToManifestEntries((HashMap)o));
			} else
				throw new IllegalArgumentException(String.valueOf(o));
		}
		return manifestEntries;
	}

	/**
	 * Convert a hierarchy of HashMap's of ManifestEntries into a series of 
	 * ManifestElement's, each of which has a full path.
	 */
	public static ManifestElement[] flatten(HashMap<String,Object> manifestElements) {
		Vector<ManifestElement> v = new Vector<ManifestElement>();
		flatten(manifestElements, v, "");
		return (ManifestElement[]) v.toArray(new ManifestElement[v.size()]);
	}

	public static void flatten(HashMap<String,Object> manifestElements, Vector<ManifestElement> v, String prefix) {
		Iterator i = manifestElements.keySet().iterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			String fullName = prefix.length() == 0 ? name : prefix+ '/' +name;
			Object o = manifestElements.get(name);
			if(o instanceof HashMap) {
				flatten((HashMap)o, v, fullName);
			} else if(o instanceof ManifestElement) {
				ManifestElement me = (ManifestElement) o;
				v.add(new ManifestElement(me, fullName));
			} else
				throw new IllegalStateException(String.valueOf(o));
		}
	}

	/**
	 * Opposite of flatten(...).
	 * Note that this can throw a ClassCastException if the vector passed in is
	 * bogus (has files pretending to be directories).
	 */
	public static <T> HashMap<String, Object> unflatten(List<ManifestElement> v) {
		HashMap<String, Object> manifestElements = new HashMap<String, Object>();
		for(ManifestElement oldElement : v) {
			add(oldElement, oldElement.getName(), manifestElements);
		}
		return manifestElements;
	}
	
	private static void add(ManifestElement e, String namePart, Map<String, Object> target) {
		int idx = namePart.indexOf('/');
		if(idx < 0) {
			target.put(namePart, new ManifestElement(e, namePart));
		} else {
			String before = namePart.substring(0, idx);
			String after = namePart.substring(idx+1);
			@SuppressWarnings("unchecked")
			HashMap<String, Object> hm = (HashMap<String, Object>) target.get(before);
			if(hm == null) {
				hm = new HashMap<String, Object>();
				target.put(before.intern(), hm);
			}
			add(e, after, hm);
		}
	}

	public int countFiles() {
		return numberOfFiles;
	}

	public long totalSize() {
		return totalSize;
	}

	@Override
	public void onMajorProgress(ObjectContainer container) {
		if(persistent())
			container.activate(cb, 1);
		cb.onMajorProgress(container);
	}

	protected void onFetchable(PutHandler handler, ObjectContainer container) {
		if(persistent()) {
			container.activate(putHandlersWaitingForFetchable, 2);
			container.activate(metadataPuttersUnfetchable, 2);
		}
		if(checkFetchable(handler)) {
			if(persistent()) {
				container.store(putHandlersWaitingForMetadata);
				container.store(this);
				container.deactivate(putHandlersWaitingForFetchable, 1);
				container.deactivate(metadataPuttersUnfetchable, 1);
				container.activate(cb, 1);
			}
			cb.onFetchable(this, container);
			if(persistent())
				container.deactivate(cb, 1);
		} else {
			if(persistent()) {
				container.deactivate(putHandlersWaitingForFetchable, 1);
				container.deactivate(metadataPuttersUnfetchable, 1);
			}
		}
	}

	private boolean checkFetchable(PutHandler handler) {
		putHandlersWaitingForFetchable.remove(handler);
		if(fetchable) return false;
		if(!putHandlersWaitingForFetchable.isEmpty()) return false;
		if(!hasResolvedBase) return false;
		if(!metadataPuttersUnfetchable.isEmpty()) return false;
		fetchable = true;
		return true;
	}

	public void onFetchable(ClientPutState state, ObjectContainer container) {
		Metadata m = (Metadata) state.getToken();
		if(persistent()) {
			container.activate(m, 100);
			container.activate(metadataPuttersUnfetchable, 2);
			container.activate(putHandlersWaitingForFetchable, 2);
		}
		if(checkFetchable(m)) {
			if(persistent()) {
				container.store(metadataPuttersUnfetchable);
				container.store(this);
				container.activate(cb, 1);
			}
			cb.onFetchable(this, container);
			if(persistent())
				container.deactivate(cb, 1);
		}
		if(persistent()) {
			container.deactivate(metadataPuttersUnfetchable, 1);
			container.deactivate(putHandlersWaitingForFetchable, 1);
		}
	}

	private synchronized boolean checkFetchable(Metadata m) {
		metadataPuttersUnfetchable.remove(m);
		if(!metadataPuttersUnfetchable.isEmpty()) return false;
		if(fetchable) return false;
		if(!putHandlersWaitingForFetchable.isEmpty()) return false;
		fetchable = true;
		return true;
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore
	}

}

