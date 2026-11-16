package twowaysql.integration;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.mendix.systemwideinterfaces.core.IContext;

public class ExtDataSourceWrapper implements DataSource {

	private DataSource ds;
	private String name;
	public ExtDataSourceWrapper(String dsName, DataSource ds) {
		this.name = dsName;
		this.ds = ds;
	}

	public boolean isTransactionStarted(IContext context) {
		Map<String, ExtConnectionWrapper> transactionMap = ExtDataSourceTransactionManager.getTransactionMap(context);
		return transactionMap.containsKey(name);
	}

	public Connection getConnection(IContext context) throws SQLException {
		// If thereis connection on context then return it, otherwise get a new connection from the underlying datasource
		Map<String, ExtConnectionWrapper> transactionMap = ExtDataSourceTransactionManager.getTransactionMap(context);
		Connection con;
		if (!transactionMap.containsKey(name)) {
			// No transaction started for this datasource, get a new connection from the underlying datasource
			con = ds.getConnection();
		} else {
			// Transaction started for this datasource, return the connection
			con = transactionMap.get(name);
			if (con == null) {
				// Connection not yet created for this transaction, get a new connection from the underlying datasource
				con = ds.getConnection();
				ExtConnectionWrapper conWrapper = new ExtConnectionWrapper(con);
				transactionMap.put(name, conWrapper);
				con = conWrapper;
			}
		}
		return con;
	}
	
	public DataSource getUnderlyingDataSource() {
		return ds;
	}

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		return ds.getParentLogger();
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		return ds.unwrap(iface);
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		return ds.isWrapperFor(iface);
	}

	@Override
	public Connection getConnection() throws SQLException {
		throw new UnsupportedOperationException("Use getConnection(IContext context) instead");
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		throw new UnsupportedOperationException("Use getConnection(IContext context) instead");
	}

	@Override
	public PrintWriter getLogWriter() throws SQLException {
		return ds.getLogWriter();
	}

	@Override
	public void setLogWriter(PrintWriter out) throws SQLException {
		ds.setLogWriter(out);
	}

	@Override
	public void setLoginTimeout(int seconds) throws SQLException {
		ds.setLoginTimeout(seconds);		
	}

	@Override
	public int getLoginTimeout() throws SQLException {
		return ds.getLoginTimeout();
	}

}
