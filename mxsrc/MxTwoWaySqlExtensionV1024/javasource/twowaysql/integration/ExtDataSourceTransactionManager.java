package twowaysql.integration;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;

public class ExtDataSourceTransactionManager {
	public static final ILogNode logger = Core.getLogger("TwoWaySql");

	public static void startTransaction(IContext context, String dsName) throws SQLException {
		if (dsName != null && !dsName.isEmpty()) {
			if (ExtDataSourceBinder.extDataSourceMap.containsKey(dsName)) {
				doStartTransaction(context, dsName);
			} else {
				throw new SQLException("Datasource not found: " + dsName);
			}
		} else {
			for (String ds : ExtDataSourceBinder.extDataSourceMap.keySet()) {
				if (!getTransactionMap(context).containsKey(ds)) {
					doStartTransaction(context, ds);
				}
			}
		}
	}
	
	private static void doStartTransaction(IContext context, String dsName) throws SQLException {
		Map<String, ExtConnectionWrapper> transactionMap = getTransactionMap(context);
		if (!transactionMap.containsKey(dsName)) {
			//Connection con = ExtDataSourceBinder.getExtDataSource(dsName).getUnderlyingDataSource().getConnection();
			//ExtConnectionWrapper conWrapper = new ExtConnectionWrapper(con);
			//transactionMap.put(dsName, conWrapper);
			transactionMap.put(dsName, null);	// Mark the transaction as started for this datasource
		} else {
			//throw new SQLException("Transaction already started for datasource: " + dsName);
			logger.warn("Transaction already started for datasource: " + dsName);
		}
	}

	public static void endTransaction(IContext context, String dsName) throws SQLException {
		if (dsName != null && !dsName.isEmpty()) {
			if (ExtDataSourceBinder.extDataSourceMap.containsKey(dsName)) {
				doEndTransaction(context, dsName);
			} else {
				throw new SQLException("Datasource not found: " + dsName);
			}
		} else {
			for (String ds : ExtDataSourceBinder.extDataSourceMap.keySet()) {
				if (getTransactionMap(context).containsKey(ds)) {
					doEndTransaction(context, ds);
				}
			}
		}
	}

	private static void doEndTransaction(IContext context, String dsName) throws SQLException {
		Map<String, ExtConnectionWrapper> transactionMap = getTransactionMap(context);
		if (transactionMap.containsKey(dsName)) {
			ExtConnectionWrapper conWrapper = transactionMap.get(dsName);
			if (conWrapper != null) {
				conWrapper.commit();
				conWrapper.closeConnection();
			}
			transactionMap.remove(dsName);
		} else {
			throw new SQLException("No transaction started for datasource: " + dsName);
		}
	}

	public static void abendTransaction(IContext context, String dsName) throws SQLException {
		if (dsName != null && !dsName.isEmpty()) {
			if (ExtDataSourceBinder.extDataSourceMap.containsKey(dsName)) {
				doAbendTransaction(context, dsName);
			} else {
				throw new SQLException("Datasource not found: " + dsName);
			}
		} else {
			for (String ds : ExtDataSourceBinder.extDataSourceMap.keySet()) {
				if (getTransactionMap(context).containsKey(ds)) {
					doAbendTransaction(context, ds);
				}
			}
		}
	}

	private static void doAbendTransaction(IContext context, String dsName) throws SQLException {
		Map<String, ExtConnectionWrapper> transactionMap = getTransactionMap(context);
		if (transactionMap.containsKey(dsName)) {
			ExtConnectionWrapper conWrapper = transactionMap.get(dsName);
			if (conWrapper != null) {
				conWrapper.rollback();
				conWrapper.closeConnection();
			}
			transactionMap.remove(dsName);
		} else {
			throw new SQLException("No transaction started for datasource: " + dsName);
		}
	}

	public static void commitTransaction(IContext context, String dsName) throws SQLException {
		if (dsName != null && !dsName.isEmpty()) {
			if (ExtDataSourceBinder.extDataSourceMap.containsKey(dsName)) {
				doCommitTransaction(context, dsName);
			} else {
				throw new SQLException("Datasource not found: " + dsName);
			}
		} else {
			for (String ds : ExtDataSourceBinder.extDataSourceMap.keySet()) {
				if (getTransactionMap(context).containsKey(ds)) {
					doCommitTransaction(context, ds);
				}
			}
		}
	}

	private static void doCommitTransaction(IContext context, String dsName) throws SQLException {
		Map<String, ExtConnectionWrapper> transactionMap = getTransactionMap(context);
		if (transactionMap.containsKey(dsName)) {
			ExtConnectionWrapper conWrapper = transactionMap.get(dsName);
			if (conWrapper != null) {
				conWrapper.commit();
			}
		} else {
			throw new SQLException("No transaction started for datasource: " + dsName);
		}
	}

	public static void rollbackTransaction(IContext context, String dsName) throws SQLException {
		if (dsName != null && !dsName.isEmpty()) {
			if (ExtDataSourceBinder.extDataSourceMap.containsKey(dsName)) {
				doRollbackTransaction(context, dsName);
			} else {
				throw new SQLException("Datasource not found: " + dsName);
			}
		} else {
			for (String ds : ExtDataSourceBinder.extDataSourceMap.keySet()) {
				if (getTransactionMap(context).containsKey(ds)) {
					doRollbackTransaction(context, ds);
				}
			}
		}
	}

	private static void doRollbackTransaction(IContext context, String dsName) throws SQLException {
		Map<String, ExtConnectionWrapper> transactionMap = getTransactionMap(context);
		if (transactionMap.containsKey(dsName)) {
			ExtConnectionWrapper conWrapper = transactionMap.get(dsName);
			if (conWrapper != null) {
				conWrapper.rollback();
			}
		} else {
			throw new SQLException("No transaction started for datasource: " + dsName);
		}
	}

	public static Map<String, ExtConnectionWrapper> getTransactionMap(IContext context) {
		Map<String, ExtConnectionWrapper> transactionMap = (Map<String, ExtConnectionWrapper>)context.getData().get("ExtDataSourceTransactionMap");
		if (transactionMap == null) {
			transactionMap = new HashMap<>();
			context.getData().put("ExtDataSourceTransactionMap", transactionMap);
		}
		return transactionMap;
	}
}
