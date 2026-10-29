package twowaysql.implementation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.MendixRuntimeException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import twowaysql.actions.AddListParameter;
import twowaysql.proxies.IdValue;
import twowaysql.proxies.LongValue;
import twowaysql.proxies.constants.Constants;

public class BatchFetchHandler {
	private long[][] ids;
	private Map<String/*ColName*/, Integer/*ColIndex*/> columnAliasMap = new HashMap<String, Integer>();
	private Map<Integer/*ColIndex*/, Map<Long/*ID*/, IMendixObject>> joineeCacheMap = new HashMap<Integer, Map<Long, IMendixObject>>();
	private long batchFetchCount;
	private long joineeCacheSize = Constants.getDEFAULT_JOINEE_CACHE_SIZE().longValue();
	private Map<String/*twoWaySqlFileName*/, String/*entityName*/> twoWaySqlMapToRetrieveNPEs = new LinkedHashMap<String, String>();
	private long nextFetchIndex = 0;
	private List<IMendixObject> fetchBuffer = new ArrayList<IMendixObject>();
	private Map<Long /*ID*/, Map<Integer /*ColIndex*/, IMendixObject>> joineePEs = new HashMap<Long, Map<Integer, IMendixObject>>();
	private Map<Long /*ID*/, Map<String /*Type*/, List<IMendixObject>>> retrievedNPEs = new HashMap<Long, Map<String, List<IMendixObject>>>();
	private long fetchBufferIndex = 0;
	private Map<String/*EntityName*/, Set<IMendixObject>/*ObjectsToCommit*/> batchCommitSets = new HashMap<String, Set<IMendixObject>>();
	private static ILogNode logger = Core.getLogger("TwoWaySql");

	public static String createBatchFetchHandler(IContext context, String twoWaySQLFileName, IMendixObject parameter, Long _batchFetchCount, Long _joineeCacheSize) throws Exception {
		BatchFetchHandler batchFetchHandler = new BatchFetchHandler();
		batchFetchHandler.batchFetchCount = _batchFetchCount != null && _batchFetchCount != 0 ? _batchFetchCount : 100;
		if (_joineeCacheSize != null && _joineeCacheSize >= 0) {
			batchFetchHandler.joineeCacheSize = _joineeCacheSize;
		}
		batchFetchHandler.loadIds(context, twoWaySQLFileName, parameter);
		batchFetchHandler.loadJoineeIntoCache(context);

		UUID uuid = UUID.randomUUID();
		String fetchHandle = uuid.toString();
		@SuppressWarnings("unchecked")
		Map<String, BatchFetchHandler> batchFetchHandlers = (Map<String, BatchFetchHandler>)context.getData()
				.get(BatchFetchHandler.class.getName());
		if (batchFetchHandlers == null) {
			batchFetchHandlers = new java.util.WeakHashMap<String, BatchFetchHandler>();
			context.getData().put(BatchFetchHandler.class.getName(), batchFetchHandlers);
		}
		batchFetchHandlers.put(fetchHandle, batchFetchHandler);
		return fetchHandle;
	}

	public static BatchFetchHandler getBatchFetchHandler(String fetchHandle, IContext context) {
		@SuppressWarnings("unchecked")
		Map<String, BatchFetchHandler> batchFetchHandlers = (Map<String, BatchFetchHandler>)context.getData()
				.get(BatchFetchHandler.class.getName());
		if (batchFetchHandlers == null) {
			throw new MendixRuntimeException("No batch fetch handlers found for handle: " + fetchHandle);
		}
		BatchFetchHandler BatchFetchHandler = batchFetchHandlers.get(fetchHandle);
		if (BatchFetchHandler == null) {
			throw new MendixRuntimeException("No batch fetch handler found for handle: " + fetchHandle);
		}
		return BatchFetchHandler;
	}

	public static boolean closeBatchFetchHandler(String fetchHandle, IContext context) throws CoreException {
		@SuppressWarnings("unchecked")
		Map<String, BatchFetchHandler> batchFetchHandlers = (Map<String, BatchFetchHandler>)context.getData()
				.get(BatchFetchHandler.class.getName());
		if (batchFetchHandlers == null) {
			return false;
		}
		BatchFetchHandler BatchFetchHandler = batchFetchHandlers.get(fetchHandle);
		if (BatchFetchHandler == null) {
			return false;
		}
		BatchFetchHandler.commitAll(context);
		batchFetchHandlers.remove(fetchHandle);
		return true;
	}
	
	public IMendixObject getNext(IContext context) throws Exception {
		if (!hasNext(context)) {
			// If there is no data remaining in the fetch buffer, it returns null.
			return null;
		}
		// Return the next object from the fetch buffer.
		IMendixObject result = fetchBuffer.get((int)fetchBufferIndex);
		fetchBufferIndex++;
		return result;
	}
	
	public IMendixObject peekNext(IContext context) throws Exception {
		if (!hasNext(context)) {
			// If there is no data remaining in the fetch buffer, it returns null.
			return null;
		}
		// Return the next object from the fetch buffer.
		IMendixObject result = fetchBuffer.get((int)fetchBufferIndex);
		return result;
	}
	
	public boolean hasNext(IContext context)  throws Exception {
		if (fetchBufferIndex < fetchBuffer.size()) {
			// There is data remaining in the fetch buffer.
			return true;
		} else {
			if (fetchNextBatch(context) > 0) {
				return true;
			} else {
				return false;
			}
		}
	}
	
	private long fetchNextBatch(IContext context) throws Exception {
		if (fetchBufferIndex < fetchBuffer.size()) {
			// If there is data remaining in the fetch buffer, do not fetch it and return the number of remaining data
			return fetchBuffer.size() - fetchBufferIndex;
		}
		if (nextFetchIndex >= ids[0].length) {
			// If the fetch buffer is empty and there is no data to retrieve, it returns zero.
			return 0L;
		} 

		// If the fetch buffer is empty and there is data to retrieve, retrieve it.
		long count = 0L;
		long fromIndex = nextFetchIndex;
		while(count == 0 && nextFetchIndex < ids[0].length) {
			List<IMendixIdentifier> midList = new ArrayList<IMendixIdentifier>();
			for (int bc = 0; bc < batchFetchCount && nextFetchIndex < ids[0].length; bc++) {
				midList.add(Core.createMendixIdentifier(ids[0][(int)nextFetchIndex]));
				nextFetchIndex++;
			}
			fetchBuffer = Core.retrieveIdList(context, midList);
			count = fetchBuffer.size();
			if (logger.isTraceEnabled()) {
				logger.trace("Batch Fetched : " + count + " / " + midList.size() + " / " + nextFetchIndex + " / " + ids[0].length);
			}
		}
		fetchBufferIndex = 0;
		joineePEs.clear();
		if (ids.length > 1) {
			// If the TwoWaySQL result has multiple columns, join the data to the fetched objects.
			retrieveJoineePEs(context, fromIndex, nextFetchIndex);
		}
		retrievedNPEs.clear();
		if (twoWaySqlMapToRetrieveNPEs.size() > 0) {
			// If there are TwoWaySQLs to retrieve related objects, retrieve them.
			retrieveRelatedNPEs(context);
		}
		return count;
	}

	public void addTwoWaySQLToRetrieveNPEs(String rwoWaySQLToRetrieveNPEs, String entityName) {
		this.twoWaySqlMapToRetrieveNPEs.put(rwoWaySQLToRetrieveNPEs, entityName);
	}

	public List<IMendixObject> getRetrievedNPEs(IContext context, IMendixObject currentObject, String type) {
		Long idValue = currentObject.getId().toLong();
		if (retrievedNPEs.containsKey(idValue)) {
			Map<String, List<IMendixObject>> typeMap = retrievedNPEs.get(idValue);
			if (typeMap.containsKey(type)) {
				return typeMap.get(type);
			} else {
				return new ArrayList<IMendixObject>();
			}
		} else {
			return new ArrayList<IMendixObject>();
		}
	}

	private void retrieveRelatedNPEs(IContext context) throws Exception {
		List<IMendixObject> idList = getIdListParameterForTwoSaySQL(context);
		for (String twoWaySQL : twoWaySqlMapToRetrieveNPEs.keySet()) {
			new AddListParameter(context, "IdList", idList).executeAction();
			List<IMendixObject> npes = new ArrayList<IMendixObject>();
			new TwoWaySqlExecutor().selectByTwoWaySql(context, twoWaySQL, null, twoWaySqlMapToRetrieveNPEs.get(twoWaySQL), npes, null, 0, false);
			for (IMendixObject npe : npes) {
				// Store the related NPE in the RetrievedNPE map.
				Long idValue = npe.getValue(context, IdValue.MemberNames.IdValue.toString());
				putRetrievedNPE(context, idValue, npe);
			}
		}
	}

	public IMendixObject getJoineePE(IContext context, IMendixObject currentObject, String colAlias) {
		Integer colIndex = columnAliasMap.get(colAlias.toLowerCase());
		if (colIndex == null) {
			throw new MendixRuntimeException("Column alias '" + colAlias + "' not found in the TwoWaySQL result.");
		}
		Long idValue = currentObject.getId().toLong();
		if (joineePEs.containsKey(idValue)) {
			Map<Integer, IMendixObject> colMap = joineePEs.get(idValue);
			if (colMap.containsKey(colIndex)) {
				return colMap.get(colIndex);
			}
		}
		return null;
	}
	
	private void retrieveJoineePEs(IContext context, long fromIndex, long toIndex) throws Exception {
		for (int col = 1; col < ids.length; col++) {
			Set<IMendixObject> cachedJoineies = new LinkedHashSet<IMendixObject>();
			Map<Long/*ID*/, IMendixObject> joineeCache = joineeCacheMap.get(col);
			Set<IMendixIdentifier> midSet = new LinkedHashSet<IMendixIdentifier>();
			Map<Long/*JoineeId*/, Set<Long>/*BaseIds*/> joineeToBase = new HashMap<Long, Set<Long>>();
			for (int row = (int)fromIndex; row < toIndex; row++) {
				if (ids[col][row] != 0L) {
					if (joineeCache != null && joineeCache.containsKey(ids[col][row])) {
						// If the joinee is in the cache, use it.
						cachedJoineies.add(joineeCache.get(ids[col][row]));
					} else {
						// If the joinee is not in the cache, add it to the midList for retrieval.
						midSet.add(Core.createMendixIdentifier(ids[col][row]));
					}
					if (!joineeToBase.containsKey(ids[col][row])) {
						joineeToBase.put(ids[col][row], new LinkedHashSet<Long>());
					}
					if (!joineeToBase.get(ids[col][row]).contains(ids[0][row])) {
						joineeToBase.get(ids[col][row]).add(ids[0][row]);
					}
				}
			}
			List<IMendixObject> retrievedJoineies = new ArrayList<IMendixObject>();
			if (!midSet.isEmpty()) {
				retrievedJoineies.addAll(Core.retrieveIdList(context, new ArrayList<IMendixIdentifier>(midSet)));
			}
			// If the joinee cache is not null, add the retrieved joineies to the cache.
			if (joineeCache != null) {
				for (IMendixObject joinee : retrievedJoineies) {
					// If the joinee is not in the cache, add it.
					if (!joineeCache.containsKey(joinee.getId().toLong())) {
						joineeCache.put(joinee.getId().toLong(), joinee);
					}
				}
			}
			// Add cached joineies to the list of joineies.
			retrievedJoineies.addAll(cachedJoineies);
			if (logger.isTraceEnabled()) {
				logger.trace("Retrieved joinees " + retrievedJoineies.size() + " (cacheHit " + cachedJoineies.size() + ") joineies for column " + col + " from index " + fromIndex + " to index " + toIndex);
			}
			
			for (IMendixObject joinee : retrievedJoineies) {
				Set<Long> baseIds = joineeToBase.get(joinee.getId().toLong());
				if (baseIds == null) {
					continue; // Skip if no base ID is found
				}
				for (Long baseId : baseIds) {
					if (joineePEs.containsKey(baseId)) {
						Map<Integer, IMendixObject> colMap = joineePEs.get(baseId);
						if (!colMap.containsKey(col)) {
							colMap.put(col, joinee);
						}
					} else {
						Map<Integer, IMendixObject> colMap = new HashMap<Integer, IMendixObject>();
						colMap.put(col, joinee);
						joineePEs.put(baseId, colMap);
					}
				}
			}
		}
	}
	
	private void putRetrievedNPE(IContext context, Long idValue, IMendixObject retrievedNPE) {
		if (retrievedNPE == null) {
			return;
		}
		if (retrievedNPEs.containsKey(idValue)) {
			Map<String, List<IMendixObject>> typeMap = retrievedNPEs.get(idValue);
			String type = retrievedNPE.getType();
			if (!typeMap.containsKey(type)) {
				typeMap.put(type, new ArrayList<IMendixObject>());
			}
			typeMap.get(type).add(retrievedNPE);
		} else {
			Map<String, List<IMendixObject>> typeMap = new HashMap<String, List<IMendixObject>>();
			String type = retrievedNPE.getType();
			List<IMendixObject> list = new ArrayList<IMendixObject>();
			list.add(retrievedNPE);
			typeMap.put(type, list);
			retrievedNPEs.put(idValue, typeMap);
		}
	}
	
	private List<IMendixObject> getIdListParameterForTwoSaySQL(IContext context) {
		java.util.List<IMendixObject> idList = new java.util.ArrayList<>(fetchBuffer.size());
		for (IMendixObject mo : fetchBuffer) {
			IMendixObject idv = Core.instantiate(context, LongValue.entityName);
			idv.setValue(context, LongValue.MemberNames.Value.toString(),
					mo.getId().toLong());
			idList.add(idv);
		}
		return idList;
	}
	
	private void loadIds(IContext context, String twoWaySqlFileName, IMendixObject parameter) throws Exception {
		List<IMendixObject> idList = new ArrayList<IMendixObject>();
		TwoWaySqlExecutor twoWaySqlExecutor = new TwoWaySqlExecutor();
		twoWaySqlExecutor.selectByTwoWaySql(context, twoWaySqlFileName, parameter, FakeMendixObject.class.getName(), idList, null, 0, false);
		if (idList.isEmpty()) {
			return;
		}

		List<String> columnNames = ((FakeMendixObject)(idList.get(0))).getAttributeNames(context);

		int colCount = columnNames.size();
		for (int col = 0; col < colCount; col++) {
			columnAliasMap.put(columnNames.get(col), col);
		}
		ids = new long[colCount][idList.size()];
		int rowCount = 0;
		for (IMendixObject row : idList) {
			for (int col = 0; col < colCount; col++) {
				Object value = row.getValue(context, columnNames.get(col));
				if (value == null) {
					value = 0L; // Default value for nulls
				} else {
					if (!(value instanceof Long)) {
						throw new MendixRuntimeException("The columns of the TwoSQL result must be LONG.");
					}
				}
				ids[col][rowCount] = (Long)value;
			}
			rowCount++;
		}
		return;
	}

	private void loadJoineeIntoCache(IContext context) throws CoreException {
		if (joineeCacheSize <= 0) {
			// If the joinee cache size is zero or negative, do not use the cache.
			if (logger.isDebugEnabled()) {
				logger.debug("Joinee cache is not used due to zero or negative cache size: " + joineeCacheSize);
			}
			return;
		}
		for (int col = 1; col < ids.length; col++) {
			// Check Cardinality
			Set<Long> unique = new HashSet<Long>();
			for (int row = 0; row < ids[col].length; row++) {
				if (ids[col][row] != 0L) {
					unique.add(ids[col][row]);
				}
			}
			if (unique.isEmpty()) {
				// If there are no unique values, do not use the cache.
				continue;
			}
			long cardinality = unique.size() * 100 / ids[col].length;
			if (cardinality > Constants.getJOINEE_CACHE_USE_THRESHOLD_CARDINALITY().longValue()) {
				// If the cardinality is above the threshold, do not use the cache.
				if (logger.isDebugEnabled()) {
					logger.debug("Joinee cache is not used for column " + col + " due to high cardinality: " + cardinality);
				}
				continue;
			}

			// Create a cache for the joinee column.
			joineeCacheMap.put(col, new LinkedHashMap<Long, IMendixObject>(16, (float) 0.75, true) {
				protected boolean removeEldestEntry(Map.Entry eldest) {
					return size() > joineeCacheSize;
				}
			});
			
			if (unique.size() <= joineeCacheSize) {
				// If the number of unique values is less than the cache size, load all values into the cache.
				List<IMendixIdentifier> midList = new ArrayList<IMendixIdentifier>();
				for (Long id : unique) {
					midList.add(Core.createMendixIdentifier(id));
				}
				List<IMendixObject> joineies = Core.retrieveIdList(context, midList);
				for (IMendixObject joinee : joineies) {
					joineeCacheMap.get(col).put(joinee.getId().toLong(), joinee);
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Joinee cache is loaded for column " + col + " with " + unique.size() + " unique values.");
				}
			} else {
				if (logger.isDebugEnabled()) {
					logger.debug("Joinee cache is not loaded for column " + col + " due to high number of unique values: " + unique.size() + " than the cache size: " + joineeCacheSize);
				}
			}
		}
	}
	
	public void batchCommit(IContext context, IMendixObject objectToCommit, Long batchCommitSize) throws CoreException {
		if (batchCommitSize == null || batchCommitSize <= 0) {
			batchCommitSize = Constants.getDEFAULT_BATCH_COMMIT_SIZE();
		}
		Set<IMendixObject> commitSet = batchCommitSets.get(objectToCommit.getType());
		if (commitSet == null) {
			commitSet = new HashSet<IMendixObject>();
			batchCommitSets.put(objectToCommit.getType(), commitSet);
		}
		commitSet.add(objectToCommit);
		if (commitSet.size() >= batchCommitSize) {
			// If the commit set is full, commit it.
			Core.commit(context, new ArrayList<IMendixObject>(commitSet));
			commitSet.clear();
		}
		if (logger.isTraceEnabled()) {
			logger.trace("Batch commit for " + objectToCommit.getType() + " with size: " + commitSet.size());
		}
	}
	
	private void commitAll(IContext context) throws CoreException {
		for (Entry<String, Set<IMendixObject>> entry : batchCommitSets.entrySet()) {
			if (entry.getValue().size() > 0) {
				Core.commit(context, new ArrayList<IMendixObject>(entry.getValue()));
				if (logger.isTraceEnabled()) {
					logger.trace("Batch commit for " + entry.getKey() + " with size: " + entry.getValue().size());
				}
				entry.getValue().clear();
			}
		}
		batchCommitSets.clear();
	}
	
}
