package twowaysql.implementation;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVPrinter;
import org.dbflute.twowaysql.SqlAnalyzer;
import org.dbflute.twowaysql.context.CommandContext;
import org.dbflute.twowaysql.context.CommandContextCreator;
import org.dbflute.twowaysql.factory.DefaultSqlAnalyzerFactory;
import org.dbflute.twowaysql.factory.SqlAnalyzerFactory;
import org.dbflute.twowaysql.node.Node;
import org.dbflute.twowaysql.pmbean.SimpleMapPmb;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.MendixRuntimeException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

import twowaysql.integration.ExtDataSourceBinder;
import twowaysql.integration.ExtDataSourceWrapper;
import twowaysql.proxies.constants.Constants;

public class TwoWaySqlExecutor {

	public static final ILogNode logger = Core.getLogger("TwoWaySql");
	private String preparedSql;
	private Object[] bindVariables = new Object[0];
	private Long dsoptions = 0L;
	private Map<String, String> resultEntityMemberNames = new HashMap<String, String>();
	private static Map<String, String> sqlMap = new ConcurrentHashMap<String, String>();
	public static final String MOCK_DIRECTIVE = "$$MOCK_";
	public static final String MOCK_EXCEL_DIRECTIVE = MOCK_DIRECTIVE + "EXCEL$$";
	public static final String MOCK_MICROFLOW_DIRECTIVE = MOCK_DIRECTIVE + "MICROFLOW$$";
	public static final String MOCK_LOGGING_DIRECTIVE = MOCK_DIRECTIVE + "LOGGING$$";

	private static ThreadLocal<Map<String, Object>> nextParameters = new ThreadLocal<Map<String, Object>>();

	public static Map<String, Object> getNextParameters() {
		if (nextParameters.get() == null) {
			nextParameters.set(new HashMap<String, Object>());
		}
		return nextParameters.get();
	}

	public static void resetParameters() {
		nextParameters.set(new HashMap<String, Object>());
		if (logger.isDebugEnabled()) {
			logger.debug("resetParameters!");
		}
	}

	public static void addParameter(String name, Object value) {
		getNextParameters().put(name, value);
		if (logger.isDebugEnabled()) {
			logger.debug("addParameter:" + name + "=" + value);
		}
	}

	public int selectByTwoWaySql(IContext context, String twoWaySqlFileName, IMendixObject parameter,
			String resultEntityType, //
			java.util.List<IMendixObject> resultList, String callBackMicroflow, int batchCommitSize, boolean countRows) throws Exception {
		int recordCount;
		String extDataSourceName = getExtDataSourceNameFromFileName(twoWaySqlFileName);
		if (extDataSourceName == null) {
			recordCount = Core.dataStorage().executeWithConnection(context, connection -> {
				int count = selectByTwoWaySql(connection, context, twoWaySqlFileName, parameter, resultEntityType,
						resultList, callBackMicroflow, batchCommitSize, countRows);
				return count;
			});
		} else {
			ExtDataSourceWrapper ds = ExtDataSourceBinder.getExtDataSource(extDataSourceName);
			if (ds == null) {
				throw new MendixRuntimeException("ExtDataSource " + extDataSourceName + "is not found.");
			}
			dsoptions = ExtDataSourceBinder.getExtDataSourceOptions(extDataSourceName);
			try (Connection con = ds.getConnection(context)) {
				recordCount = selectByTwoWaySql(con, context, twoWaySqlFileName, parameter, resultEntityType,
						resultList, callBackMicroflow, batchCommitSize, countRows);
			} catch (SQLException e) {
				throw new MendixRuntimeException(e);
			}
		}
		return recordCount;
	}

	public int selectByTwoWaySql(Connection connection, IContext context, String twoWaySqlFileName,
			IMendixObject parameter, String resultEntityType, //
			java.util.List<IMendixObject> resultList, String callBackMicroflow, int batchCommitSize, boolean countRows) throws RuntimeException {
		int count = 0;
		try {
			if (!countRows) {
				setupResultEntityMemberNames(context, resultEntityType);
			}
			setupTwoWaySql(context, twoWaySqlFileName, parameter);
			if (countRows) {
				this.preparedSql = "SELECT COUNT(*) FROM ( " + this.preparedSql + " ) AS count_table";
			}
			try (PreparedStatement stmt = connection.prepareStatement(this.preparedSql)) {
				setBindVariables(stmt);
				setupSlowQueryDetection();
				try (ResultSet rset = stmt.executeQuery()) {
					reportSlowQuery();
					ResultSetMetaData rmd = rset.getMetaData();
					int colCount = rmd.getColumnCount();
					while ( rset.next()) {
						if (countRows) {
							count = rset.getInt(1);
							break;
						}
						IMendixObject obj = readToMendixObject(context, resultEntityType, rset, colCount, rmd);
						if (resultList != null) {
							resultList.add(obj);
							if (batchCommitSize != 0 && resultList.size() >= batchCommitSize) {
								// InsertSelectPeByTwoWaySqlの場合なので必要ならコールバックしてコミット
								if (callBackMicroflow != null) {
									Core.microflowCall(callBackMicroflow).inTransaction(true). //
										withParam("InsertEntityList", resultList). //
										execute(context);
								}
								Core.commit(context, resultList);
								resultList.clear();
							}
						}
						if (batchCommitSize == 0 && callBackMicroflow != null) {
							// CursorLoopOnTwoWaySqlの場合なのでコールバック
							Core.microflowCall(callBackMicroflow).inTransaction(true). //
									withParam("CurrentObject", obj). //
									withParams(ParameterUtilForCursorLoop.getNextParameters()). //
									execute(context);
						}
						count += 1;
					}
					if (resultList != null && batchCommitSize != 0 && resultList.size() > 0) {
						// InsertSelectPeByTwoWaySqlの場合なので必要ならコールバックしてコミット
						if (callBackMicroflow != null) {
							Core.microflowCall(callBackMicroflow).inTransaction(true). //
								withParam("InsertEntityList", resultList). //
								execute(context);
						}
						Core.commit(context, resultList);
						resultList.clear();
					}
				}
			}
			if (logger.isDebugEnabled()) {
				logger.debug("selectByTwoWaySql: PreparedSql=\n" + preparedSql + "\nBindVariables="
						+ toStringBindVariables() + "\nResultCount=" + count);
			}
		} catch (Exception e) {
			if (preparedSql != null) {
				logger.error("selectByTwoWaySql: PreparedSql=\n" + preparedSql + "\nBindVariables="
						+ toStringBindVariables() + "\nResultCount=" + count);
			}
			logger.error("Failed to execute sql statement: " + e.getMessage(), e);
			throw new MendixRuntimeException(e);
		} finally {
			ParameterUtilForCursorLoop.resetParameters();
		}
		return count;
	}

	public java.util.List<IMendixObject> selectExtDbByStandardSql(IContext context, String extDataSourceName, String sql, List<Object> parameters,
			String resultEntityType	 ) throws Exception {
		java.util.List<IMendixObject> resultList = new ArrayList<IMendixObject>();
		ExtDataSourceWrapper ds = ExtDataSourceBinder.getExtDataSource(extDataSourceName);
		if (ds == null) {
			throw new MendixRuntimeException("ExtDataSource " + extDataSourceName + "is not found.");
		}
		dsoptions = ExtDataSourceBinder.getExtDataSourceOptions(extDataSourceName);
		try (Connection con = ds.getConnection(context)) {
			setupResultEntityMemberNames(context, resultEntityType);
			try (PreparedStatement stmt = con.prepareStatement(sql)) {
				if (parameters != null) {
					for (int i = 0; i < parameters.size(); i++) {
						stmt.setObject(i + 1, parameters.get(i));
					}
				}
				setupSlowQueryDetection();
				try (ResultSet rset = stmt.executeQuery()) {
					reportSlowQuery();
					ResultSetMetaData rmd = rset.getMetaData();
					int colCount = rmd.getColumnCount();
					while (rset.next()) {
						IMendixObject obj = readToMendixObject(context, resultEntityType, rset, colCount, rmd);
						resultList.add(obj);
					}
				}
			}
			if (logger.isDebugEnabled()) {
				logger.debug("selectExtDbByStandardSql: Sql=\n" + sql + "\nparameters="
						+ parameters + "\nResultCount=" + resultList.size());
			}
		} catch (SQLException e) {
			throw new MendixRuntimeException("selectExtDbByStandardSql: Sql=\n" + sql + "\nparameters=" + parameters, e);
		}
		return resultList;
	}

	public IMendixObject readToMendixObject(IContext context,String resultEntityType, ResultSet rset, int colCount, ResultSetMetaData rmd) throws SQLException {
		IMendixObject obj = null;
		if (FakeMendixObject.class.getName().equals(resultEntityType)) {
			obj = new FakeMendixObject();
		} else {
			obj = Core.instantiate(context, resultEntityType);
		}
		for (int colIdx = 1; colIdx <= colCount; colIdx++) {
			String colName = rmd.getColumnLabel(colIdx);
			String entityMemberName = resultEntityMemberNames.get(colName.toLowerCase());
			if (entityMemberName != null) {
				var memberValue = rset.getObject(colIdx);
				if (memberValue instanceof java.sql.Timestamp) {
					if ((dsoptions & Constants.getEXDS_WO_RSLT_TZ_ADJST()) == 0L) {
						// EXDS_WO_RSLT_TZ_ADJST指定無し → 実行環境のタイムゾーン時刻への調整あり
						// このケースはDBにタイムゾーン無しのUTC時刻でストアされていてJDBCが何の調整もしていない前提(Mendixでは標準)
						// そのため取得したjava.sql.Timestampで一旦UTCタイムゾーンのZonedDateTimeを生成して
						// そのZonedDateTimeからava.util.Dateを生成する事で実行環境のタイムゾーンに時刻を調整する
						
						var timeStamp = (java.sql.Timestamp) memberValue; // DBの値のままで取れる(UTC)
						// Timestamp型をそのままEntityにセットすると実行環境のタイムゾーン分ずれる
						// timeStampをそのままInstantにしても実行環境のタイムゾーン分ずれるのでLocalDateTimeを経由してからDateにする
						var ldt = timeStamp.toLocalDateTime();
						var zdt = ldt.atZone(ZoneOffset.UTC); // Dateに変換するためZonedDateTimeにする。DB上はUTCなのでUTC指定
						var date = java.util.Date.from(zdt.toInstant()); // MendixのためにDateに戻す. ここで実行環境のタイムゾーンのDateになるが問題ない
						obj.setValue(context, entityMemberName, date);
					} else {
						// EXDS_WO_RSLT_TZ_ADJST指定ありなので時刻調整なし
						// こちらのケースはDBにタイムゾーンありでストアされている(もしくはJDBC内で実行環境のタイムゾーンへの時刻調整が済んでいる)前提
						// java.sql.TimestampのままsetValue()することで時刻調整無しで実行環境のタイムゾーンだ付与される
						obj.setValue(context, entityMemberName, memberValue);
					}
				} else {
					obj.setValue(context, entityMemberName, memberValue);
				}
			}
		}
		return obj;		
	}
	
	public int updateByTwoWaySql(IContext context, String twoWaySqlFileName, IMendixObject parameter)
			throws Exception {
		int updateCount;
		String extDataSourceName = getExtDataSourceNameFromFileName(twoWaySqlFileName);
		if (extDataSourceName == null) {
			updateCount = Core.dataStorage().executeWithConnection(context, connection -> {
				int count = updateByTwoWaySql(connection, context, twoWaySqlFileName, parameter);
				return count;
			});
		} else {
			ExtDataSourceWrapper ds = ExtDataSourceBinder.getExtDataSource(extDataSourceName);
			if (ds == null) {
				throw new MendixRuntimeException("ExtDataSource " + extDataSourceName + "is not found.");
			}
			dsoptions = ExtDataSourceBinder.getExtDataSourceOptions(extDataSourceName);
			try (Connection con = ds.getConnection(context)) {
				updateCount = updateByTwoWaySql(con, context, twoWaySqlFileName, parameter);
			} catch (SQLException e) {
				throw new MendixRuntimeException(e);
			}
		}
		return updateCount;
	}

	public int updateByTwoWaySql(Connection connection, IContext context, String twoWaySqlFileName,
			IMendixObject parameter)
			throws RuntimeException {
		int count = 0;
		try {
			setupTwoWaySql(context, twoWaySqlFileName, parameter);

			try (PreparedStatement stmt = connection.prepareStatement(this.preparedSql)) {
				setBindVariables(stmt);
				setupSlowQueryDetection();
				count = stmt.executeUpdate();
				reportSlowQuery();
			}
			if (logger.isDebugEnabled()) {
				logger.debug("updateByTwoWaySql: PreparedSql=\n" + preparedSql + "\nBindVariables="
						+ toStringBindVariables() + "\nResultCount=" + count);
			}
		} catch (Exception e) {
			if (preparedSql != null) {
				logger.error("updateByTwoWaySql: PreparedSql=\n" + preparedSql + "\nBindVariables="
					+ toStringBindVariables() + "\nResultCount=" + count);
			}
			logger.error("Failed to execute sql statement: " + e.getMessage(), e);
			throw new MendixRuntimeException(e);
		}
		return count;
	}

	public int callByTwoWaySql(IContext context, String twoWaySqlFileName, IMendixObject parameter, String resultEntityType, //
			java.util.List<IMendixObject> resultList)
			throws Exception {
		int resultCount;
		String extDataSourceName = getExtDataSourceNameFromFileName(twoWaySqlFileName);
		if (extDataSourceName == null) {
			resultCount = Core.dataStorage().executeWithConnection(context, connection -> {
				int count = callByTwoWaySql(connection, context, twoWaySqlFileName, parameter, resultEntityType, resultList);
				return count;
			});
		} else {
			ExtDataSourceWrapper ds = ExtDataSourceBinder.getExtDataSource(extDataSourceName);
			if (ds == null) {
				throw new MendixRuntimeException("ExtDataSource " + extDataSourceName + "is not found.");
			}
			dsoptions = ExtDataSourceBinder.getExtDataSourceOptions(extDataSourceName);
			try (Connection con = ds.getConnection(context)) {
				resultCount = callByTwoWaySql(con, context, twoWaySqlFileName, parameter, resultEntityType, resultList);
			} catch (SQLException e) {
				throw new MendixRuntimeException(e);
			}
		}
		return resultCount;
	}

	public int callByTwoWaySql(Connection connection, IContext context, String twoWaySqlFileName, //
			IMendixObject parameter, String resultEntityType, java.util.List<IMendixObject> resultList)
			throws RuntimeException {
		int resultCount = 0;
		try {
			setupTwoWaySql(context, twoWaySqlFileName, parameter);

			try (CallableStatement stmt = connection.prepareCall(this.preparedSql)) {
				setBindVariables(stmt);
				setupSlowQueryDetection();
				boolean hasResults = stmt.execute();
				reportSlowQuery();
				if (hasResults) {
					try (ResultSet rset = stmt.getResultSet()) {
						ResultSetMetaData rmd = rset.getMetaData();
						int colCount = rmd.getColumnCount();
						while (rset.next()) {
							IMendixObject obj = readToMendixObject(context, resultEntityType, rset, colCount, rmd);
							if (resultList != null) {
								resultList.add(obj);
							}
							resultCount += 1;
						}
					}
				} else {
					int count = stmt.getUpdateCount();
					IMendixObject obj = Core.instantiate(context, resultEntityType);
					obj.setValue(context, "Value", Long.valueOf(count));
					resultList.add(obj);
					resultCount += 1;
				}
			}
			if (logger.isDebugEnabled()) {
				logger.debug("callByTwoWaySql: PreparedSql=\n" + preparedSql + "\nBindVariables="
						+ toStringBindVariables() + "\nResultCount=" + resultList.size());
			}
		} catch (Exception e) {
			if (preparedSql != null) {
				logger.error("callByTwoWaySql: PreparedSql=\n" + preparedSql + "\nBindVariables="
						+ toStringBindVariables() + "\nResultCount=" + resultList.size());
			}
			logger.error("Failed to execute sql statement: " + e.getMessage(), e);
			throw new MendixRuntimeException(e);
		}
		return resultCount;
	}

		
	public int deleteByTwoWaySql(IContext context, String twoWaySqlFileName, IMendixObject parameter, int batchDeleteSize)
			throws Exception {
		int updateCount = Core.dataStorage().executeWithConnection(context, connection -> {
			int count = deleteByTwoWaySql(connection, context, twoWaySqlFileName, parameter, batchDeleteSize);
			return count;
		});
		return updateCount;
	}

	public int deleteByTwoWaySql(Connection connection, IContext context, String twoWaySqlFileName,
			IMendixObject parameter, int batchDeleteSize)
			throws RuntimeException {
		int count = 0;
		try {
			setupTwoWaySql(context, twoWaySqlFileName, parameter);

			try (PreparedStatement stmt = connection.prepareStatement(this.preparedSql)) {
				setBindVariables(stmt);
				setupSlowQueryDetection();
				try (ResultSet rset = stmt.executeQuery()) {
					reportSlowQuery();
					List<IMendixIdentifier> midList = new ArrayList<IMendixIdentifier>();
					while (rset.next()) {
						Long idValue = rset.getLong(1);
						midList.add(Core.createMendixIdentifier(idValue));
						if (midList.size() >= batchDeleteSize) {
							List<IMendixObject> batchDeleteList = Core.retrieveIdList(context, midList);
							Core.delete(context, batchDeleteList);
							midList.clear();
						}
						count += 1;
					}
					if (midList.size() > 0) {
						List<IMendixObject> batchDeleteList = Core.retrieveIdList(context, midList);
						Core.delete(context, batchDeleteList);
						midList.clear();
					}
				}
			}

			if (logger.isDebugEnabled()) {
				logger.debug("deleteByTwoWaySql: PreparedSql=\n" + preparedSql + "\nBindVariables="
						+ toStringBindVariables() + "\nResultCount=" + count);
			}
		} catch (Exception e) {
			if (preparedSql != null) {
				logger.error("deleteByTwoWaySql: PreparedSql=\n" + preparedSql + "\nBindVariables="
						+ toStringBindVariables() + "\nResultCount=" + count);
			}
			logger.error("Failed to execute sql statement: " + e.getMessage(), e);
			throw new MendixRuntimeException(e);
		}
		return count;
	}

	public int exportCsvByTwoWaySql(IContext context, String twoWaySqlFileName, IMendixObject parameter,
			CSVPrinter csvPrinter, String dateTimeFormat, ZoneId userZone) throws Exception {
		int recordCount = 0;
		String extDataSourceName = getExtDataSourceNameFromFileName(twoWaySqlFileName);
		if (extDataSourceName == null) {
			recordCount = Core.dataStorage().executeWithConnection(context, connection -> {
				int count = exportCsvByTwoWaySql(connection, context, twoWaySqlFileName, parameter, csvPrinter,
						dateTimeFormat, userZone);
				return count;
			});
		} else {
			ExtDataSourceWrapper ds = ExtDataSourceBinder.getExtDataSource(extDataSourceName);
			if (ds == null) {
				throw new MendixRuntimeException("ExtDataSource " + extDataSourceName + "is not found.");
			}
			dsoptions = ExtDataSourceBinder.getExtDataSourceOptions(extDataSourceName);
			try (Connection con = ds.getConnection(context)) {
				recordCount = exportCsvByTwoWaySql(con, context, twoWaySqlFileName, parameter, csvPrinter,
						dateTimeFormat, userZone);
			} catch (SQLException e) {
				throw new MendixRuntimeException(e);
			}
		}
		return recordCount;
	}

	public int exportCsvByTwoWaySql(Connection connection, IContext context, String twoWaySqlFileName,
			IMendixObject parameter,
			CSVPrinter csvPrinter, String dateTimeFormat, ZoneId userZone) throws RuntimeException {
		int count = 0;
		try {
			setupTwoWaySql(context, twoWaySqlFileName, parameter);

			try (PreparedStatement stmt = connection.prepareStatement(this.preparedSql)) {
				setBindVariables(stmt);
				setupSlowQueryDetection();
				try (ResultSet rset = stmt.executeQuery()) {
					reportSlowQuery();
					ResultSetMetaData rmd = rset.getMetaData();
					int colCount = rmd.getColumnCount();
					final List<String> headerNames = new ArrayList<String>();
					for (int colIdx = 1; colIdx <= colCount; colIdx++) {
						String colName = rmd.getColumnLabel(colIdx);
						headerNames.add(colName);
					}
					csvPrinter.printRecord(headerNames);
					final List<Object> values = new ArrayList<Object>();

					// mod by sugita.h (2022/09/30) start
					// cut by youta-s 2022/11/09
					//					    ZoneId userZone;
					//						if(context.getSession().getTimeZone() != null) {
					//							userZone = context.getSession().getTimeZone().toZoneId();
					//						} else {
					//							IMendixObject __timeZone = Core.microflowCall("Common.SUB_GetTimezoneByPlantCodeConstant").inTransaction(true).execute(context);
					//							system.proxies.TimeZone timeZone = __timeZone == null ? null : system.proxies.TimeZone.initialize(context, __timeZone);
					//							userZone = TimeZone.getTimeZone(timeZone.getCode()).toZoneId();
					//						}
					// cut by youta-s 2022/11/09
					DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern(dateTimeFormat);

					DecimalFormat df = new DecimalFormat("#.########");
					// mod by sugita.h (2022/09/30) end

					while (rset.next()) {
						for (int colIdx = 1; colIdx <= colCount; colIdx++) {
							Object value = rset.getObject(colIdx);
							// mod by sugita.h (2022/09/30) start
							if (value instanceof BigDecimal) {
								values.add(df.format(((BigDecimal) value).stripTrailingZeros()));
							} else if (value instanceof Timestamp) {
								var date = (Date) value;
								if ((dsoptions & Constants.getEXDS_WO_RSLT_TZ_ADJST()) == 0) {
									// EXDS_WO_RSLT_TZ_ADJST指定無し → csv出力で指定されたのタイムゾーン時刻への調整あり
									// selectByTwoWaySqlと違ってcsvに文字列で出力してしまうためこの先で利用者の地域のタイムゾーンに時刻を合わせるタイミングが無い。
									// そのためここでcsv出力で指定のタイムゾーンに合わせる
									// ※たかしこの実装がDateにキャストして処理してるので微妙にselectByTwoWaySqlと違ってて気持ち悪いけど放置 2025/06/12 youta-s

									// 取得したjava.sql.Timestampで一旦UTCタイムゾーンのZonedDateTimeを生成して
									// そのZonedDateTimeからcsv出力で指定されたタイムゾーンに時刻を調整する
									var atUTC = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
											.atZone(ZoneId.of("UTC"));
									var dateForUser = atUTC.toInstant().atZone(userZone);
									values.add(dateForUser.format(dateFormat));
								} else {
									// EXDS_WO_RSLT_TZ_ADJST指定ありなので時刻調整なし
									// こちらのケースはDBにタイムゾーンありでストアされている(もしくはJDBC内で実行環境のタイムゾーンへの時刻調整が済んでいる)前提

									// 取得したjava.sql.Timestampで一旦実行環境のタイムゾーンのZonedDateTimeを生成して
									// そのZonedDateTimeからcsv出力で指定されたタイムゾーンに時刻を調整する
									var atSystemZone = date.toInstant().atZone(ZoneId.systemDefault());
									var dateForUser = atSystemZone.toInstant().atZone(userZone);
									values.add(dateForUser.format(dateFormat));									
								}
							}
							// mod by sugita.h (2022/09/30) end
							else {
								values.add(value);
							}
						}
						csvPrinter.printRecord(values);
						values.clear();
						count += 1;
					}
				}
			}
			if (logger.isDebugEnabled()) {
				logger.debug("exportCsvByTwoWaySql: PreparedSql=\n" + preparedSql + "\nBindVariables="
						+ toStringBindVariables() + "\nResultCount=" + count);
			}
		} catch (Exception e) {
			if (preparedSql != null) {
				logger.error("exportCsvByTwoWaySql: PreparedSql=\n" + preparedSql + "\nBindVariables="
						+ toStringBindVariables() + "\nResultCount=" + count);
			}
			logger.error("Failed to execute sql statement: " + e.getMessage(), e);
			throw new MendixRuntimeException(e);
		}
		return count;
	}

	private void setupTwoWaySql(IContext context, String twoWaySqlFileName, IMendixObject parameter)
			throws Exception {
//		BoundDateDisplayStyle boundDateDisplayStyle = createBoundDateDisplayStyle(TimeZone.getDefault());
		SimpleMapPmb<Object> pmb = convertIMendixObject2SimpleMapPmb(parameter, context);
		String twoWaySQL;
		if (twoWaySqlFileName.startsWith("--")) {
			twoWaySQL = twoWaySqlFileName;
		} else {
			twoWaySQL = readSql(twoWaySqlFileName);
		}
		if (twoWaySQL.startsWith(MOCK_DIRECTIVE)) {
			throw new MendixRuntimeException("MOCK functionis are not allowed on the Action you are using.");
		}
		setupTwoWaySql2PreparedSql(new DefaultSqlAnalyzerFactory(), twoWaySQL, pmb/*, boundDateDisplayStyle*/);
	}

	private void setBindVariables(PreparedStatement stmt) throws Exception {
		for (int i = 0; i < bindVariables.length; i++) {
			stmt.setObject(i + 1, bindVariables[i]);
		}
	}

	private String toStringBindVariables() {
		StringBuilder bindValStr = new StringBuilder();
		for (Object o : bindVariables) {
			if (bindValStr.length() != 0) {
				bindValStr.append(" : ");
			}
			bindValStr.append((o != null) ? o.toString() : "null");
		}
		return bindValStr.toString();
	}

	private SimpleMapPmb<Object> convertIMendixObject2SimpleMapPmb(IMendixObject mp, IContext context) throws Exception {
		SimpleMapPmb<Object> mapPmb = new SimpleMapPmb<Object>();
		if (mp != null) {
			Map<String, ? extends IMendixObjectMember<?>> momMap = mp.getMembers(context);
			for (String key : momMap.keySet()) {
				mapPmb.addParameter(key, mxParam2DbfluteParam(momMap.get(key).getValue(context)));
			}
		}
		try {
			Map<String, Object> contextParameters = getNextParameters();
			for (String key : contextParameters.keySet()) {
				Object o = contextParameters.get(key);
				if (o instanceof List) {
					List<IMendixObject> orglist = (List<IMendixObject>) o;
					if (orglist.size() > 0 && orglist.get(0).getMetaObject().getName().endsWith("Value")
											&& orglist.get(0).getMetaObject().getMetaPrimitive("Value") != null
											&& orglist.get(0).getMetaObject().getMetaPrimitives().size() == 1) {
						// Entity名がValueで終わっていてValueという項目のみであればin句用のListとみなして値のみを取り出す
						ArrayList<Object> newlist = new ArrayList<Object>();
						for (IMendixObject olo : orglist) {
							Object oloVal = olo.getValue(context, "Value");
							newlist.add(mxParam2DbfluteParam(oloVal));
						}
						mapPmb.addParameter(key, newlist);
					} else {
						// in句用以外ならプロキシのリストに変換して設定
						List<IMendixObject> moList = (List<IMendixObject>) o;
						List<Object> proxyList = new ArrayList<Object>();
						for (IMendixObject mo : moList) {
							proxyList.add(toProxyObject(context, mo));
						}
						mapPmb.addParameter(key, proxyList);
					}
				} else {
					mapPmb.addParameter(key, mxParam2DbfluteParam(o));
				}
			}
		} finally {
			resetParameters();
		}
		return mapPmb;
	}

	private static Object toProxyObject(IContext mxcontext, IMendixObject mo) throws Exception {
		if (mo == null) {
			return null;
		}
		IMetaObject meta = mo.getMetaObject();
		String proxyClassName = meta.getModuleName().toLowerCase() + ".proxies."
				+ meta.getName().replace(meta.getModuleName() + ".", "");
		Class clazz = Class.forName(proxyClassName);
		Method initializeer = clazz.getDeclaredMethod("initialize", IContext.class, IMendixObject.class);
		Object proxy = initializeer.invoke(null, mxcontext, mo);
		return proxy;
	}

	private Object mxParam2DbfluteParam(Object o) {
		if (o != null && o instanceof String && ((String) o).equals("")) {
			return null;
		} else if (o != null && o instanceof java.util.Date) {
			if ((dsoptions & Constants.getEXDS_WO_PRAM_TZ_ADJST()) == 0) {	// EXDS_WO_PRAM_TZ_ADJST指定無し→UTC時刻への調整あり
				var date = (java.util.Date) o; // JavaでDateにする際に実行環境のタイムゾーンになる
				var utcZonedDatetime = date.toInstant().atZone(ZoneOffset.UTC); // DBにはUTCのまま入れるのでタイムゾーンを変える
				return java.sql.Timestamp.valueOf(utcZonedDatetime.toLocalDateTime()); // LocalDateTimeと同じ時間のTimeStampeができる
			} else {
				// EXDS_WO_PRAM_TZ_ADJST指定ありなのでUTC時刻への調整なし
				return new java.sql.Timestamp(((java.util.Date)o).getTime());
			}
		} else {
			return o;
		}
	}

	private void setupResultEntityMemberNames(IContext context, String resultEntityType) {
		if (FakeMendixObject.class.getName().equals(resultEntityType)) {
			// FakeMendixObjectの場合はそのままメンバー名をキーにして値を返す
			resultEntityMemberNames  = new HashMap<String, String>() {
					public String get(Object key) {
						return (String) key; // FakeMendixObjectはキーがそのままメンバー名
					}
			};
			return;
		}
		IMendixObject mp = Core.instantiate(context, resultEntityType);
		Map<String, ? extends IMendixObjectMember<?>> momMap = mp.getMembers(context);
		for (String key : momMap.keySet()) {
			resultEntityMemberNames.put(key.toLowerCase(), key);
		}
	}

	private void setupTwoWaySql2PreparedSql(SqlAnalyzerFactory factory, String twoWaySql, Object arg/*,
			BoundDateDisplayStyle dateDisplayStyle*/) {
		final String[] argNames = new String[] { "pmb" };
		final Class<?>[] argTypes = new Class<?>[] { arg.getClass() };
		final Object[] args = new Object[] { arg };
		setupTwoWaySql2PreparedSql(factory, twoWaySql, argNames, argTypes, args/*, dateDisplayStyle*/);
		return;
	}

	private void setupTwoWaySql2PreparedSql(SqlAnalyzerFactory factory, String twoWaySql, String[] argNames,
			Class<?>[] argTypes,
			Object[] args/*, BoundDateDisplayStyle dateDisplayStyle*/) {
		final CommandContext context;
		{
			final SqlAnalyzer parser = createSqlAnalyzer4PreparedSql(factory, twoWaySql);
			final Node node = parser.analyze();
			final CommandContextCreator creator = new CommandContextCreator(argNames, argTypes);
			context = creator.createCommandContext(args);
			node.accept(context);
		}
		preparedSql = context.getSql();
		bindVariables = context.getBindVariables();
		if (logger.isTraceEnabled()) {
			logger.trace("setupTwoWaySql2PreparedSql: TwoWaySql=\n" + twoWaySql + "\nPreparedSql=\n" + preparedSql + "\nBindVariables="
					+ toStringBindVariables());
		}
		return;
	}

	private SqlAnalyzer createSqlAnalyzer4PreparedSql(SqlAnalyzerFactory factory, String twoWaySql) {
		if (factory == null) {
			String msg = "The factory of SQL analyzer should exist.";
			throw new IllegalStateException(msg);
		}
		final boolean blockNullParameter = false;
		final SqlAnalyzer created = factory.create(twoWaySql, blockNullParameter);
		if (created != null) {
			return created;
		}
		String msg = "The factory should not return null:";
		msg = msg + " sql=" + twoWaySql + " factory=" + factory;
		throw new IllegalStateException(msg);
	}

//	当初から使っていないのに気づかずにいた。DBFluteのバージョンアップで気がついて削除。	2025/10/03 youta-s
//	private BoundDateDisplayStyle createBoundDateDisplayStyle(TimeZone finalTimeZone) {
//		// BoundDateDisplayStyle (from CB) cannot be serializable so use default
//		final String datePattern = "yyyy/MM/dd";
//		final String timestampPattern = "yyyy/MM/dd HH:mm:ss.SSS";
//		final String timePattern = "HH:mm:ss";
//		return new BoundDateDisplayStyle(datePattern, timestampPattern, timePattern, () -> {
//			return finalTimeZone;
//		});
//	}

	public static String getExtDataSourceNameFromFileName(String twoWaySqlFileName) throws IOException {
		if (twoWaySqlFileName.startsWith("--")) {
			return null;
		}
		return getExtDataSourceNameFromSql(readSql(twoWaySqlFileName));
	}

	public static String getExtDataSourceNameFromSql(String sql) {
		Pattern p1 = Pattern.compile("--[ \t]*@(.*?)@");
		Matcher m1 = p1.matcher(sql);
		if (m1.find()) {
			return m1.group(1);
		} else {
			return null;
		}
	}

	/**
	 * SQLファイルの読み込み
	 * @param sqlFilePath
	 * @return
	 * @throws IOException
	 */
	public static String readSql(String sqlFilePath) throws IOException {
		String sqlFile = sqlFilePath.replace("\\", "/");
		String sqlString = null;
		if (logger.isDebugEnabled()) {
			sqlMap.clear(); // デバッグモードでは毎回読み込むのでキャッシュしない
		} else {
			// デバッグモードでは毎回読み込むのでキャッシュしない
			sqlString = sqlMap.get(sqlFile);
			if (sqlString != null) {
				//logger.info("readSql: Cache hit sqlFile=" + sqlFile);
				return sqlString;
			}
		}

		StringBuilder sql = new StringBuilder();
		try (InputStream is = new FileInputStream(
				Core.getConfiguration().getResourcesPath() + System.getProperty("file.separator") + "sql"
						+ System.getProperty("file.separator") + sqlFile);
				BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
			String lineData;
			while ((lineData = br.readLine()) != null) {
				sql.append(lineData + "\n");
			}

		} catch (IOException e) {
			throw new MendixRuntimeException(e);
		}

		sqlString = sql.toString();
		if (logger.isDebugEnabled()) {
			logger.debug("readSql: sqlFile=" + sqlFile + "\nSQL=\n" + sqlString);
			// デバッグモードでは毎回読み込むのでキャッシュしない
		} else {
			//logger.info("readSql: Cached sqlFile=" + sqlFile);
			sqlMap.put(sqlFile, sqlString);
		}
		return sqlString;
	}

	/*
	 * Additional functions for slow query detection
	 */
	private static long slowQueryThresholdMillis = -1;
	private long queryStartTime;

	public void setupSlowQueryDetection() {
		if (slowQueryThresholdMillis < 0) {
			slowQueryThresholdMillis = Constants.getSlowQueryThresholdMillis();
		}
		queryStartTime = System.currentTimeMillis();
	}

	public void reportSlowQuery() {
		long queryDuration = System.currentTimeMillis() - queryStartTime;
		if (queryDuration >= slowQueryThresholdMillis) {
			logger.warn("Query executed in " + queryDuration / 1000 + " seconds and " + queryDuration % 1000
					+ " milliseconds.\n" +
					"SQL: " + preparedSql + "\nPARAMS:" + toStringBindVariables());
		}
	}

	/*
	 * Additional functions for InLineCursorLoop
	 */
	private class InLineCursorLoopStatus {
		public Connection connection;
		public PreparedStatement statement;
		public ResultSet resultSet;
		public ResultSetMetaData resultSetMetaData;
		public int columnCount;
		public InLineCursorLoopStatus(Connection connection, PreparedStatement statement, ResultSet resultSet, ResultSetMetaData resultSetMetaData, int columnCount) {
			this.connection = connection;
			this.statement = statement;
			this.resultSet = resultSet;
			this.resultSetMetaData = resultSetMetaData;
			this.columnCount = columnCount;
		}
	}
	
	private InLineCursorLoopStatus iclStatus = null;

	public void openInLineCursor(IContext context, String twoWaySqlFileName, IMendixObject parameter) throws Exception {
		String extDataSourceName = getExtDataSourceNameFromFileName(twoWaySqlFileName);
		if (extDataSourceName == null) {
			throw new MendixRuntimeException("openInLineCursor is only available for external data source.");
		}
		ExtDataSourceWrapper ds = ExtDataSourceBinder.getExtDataSource(extDataSourceName);
		if (ds == null) {
			throw new MendixRuntimeException("ExtDataSource " + extDataSourceName + "is not found.");
		}
		Connection con = null;
		PreparedStatement stmt = null;
		ResultSet rset = null;
		ResultSetMetaData rmd = null;
		int colCount = 0;
		try {
			con = ds.getConnection(context);
			setupTwoWaySql(context, twoWaySqlFileName, parameter);
			stmt = con.prepareStatement(this.preparedSql);
			setBindVariables(stmt);
			setupSlowQueryDetection();
			rset = stmt.executeQuery();
			reportSlowQuery();
			rmd = rset.getMetaData();
			colCount = rmd.getColumnCount();
		} catch (Exception e) {
			if (rset != null) {
				rset.close();
			}
			if (stmt != null) {
				stmt.close();
			}
			if (con != null) {
				con.close();
			}
			throw e;
		}
		this.iclStatus = new InLineCursorLoopStatus(con, stmt, rset, rmd, colCount);
	}
	
	public IMendixObject readNextInLineCursor(IContext context, String resultEntityType) throws Exception {
		if (this.iclStatus == null) {
			throw new MendixRuntimeException("InLineCursor is not opened.");
		}
		if (this.iclStatus.resultSet.next() == false) {
			return null;
		}
		if (resultEntityMemberNames.size() == 0) {
			setupResultEntityMemberNames(context, resultEntityType);
		}
		return readToMendixObject(context, resultEntityType, this.iclStatus.resultSet, this.iclStatus.columnCount, this.iclStatus.resultSetMetaData);
	}
	
	public void closeInLineCursor() throws Exception {
		if (this.iclStatus == null) {
			throw new MendixRuntimeException("InLineCursor is not opened.");
		}
		this.iclStatus.resultSet.close();
		this.iclStatus.statement.close();
		this.iclStatus.connection.close();
		this.iclStatus = null;
	}

	/*
	 * Additional functions for mocking
	 */
	public interface TwoWaySqlMocker {
		public java.util.List<IMendixObject> mockRetrieveByTwoWaySql(IContext context, String mockDirective, Map<String, Object> paramMap, String resultEntityType) throws Exception;
		public java.lang.Long mockCountRowsByTwoWaySql(IContext context, String mockDirective, Map<String, Object> paramMap) throws Exception;
		public java.lang.Long mockUpdateByTwoWaySql(IContext context, String mockDirective, Map<String, Object> paramMap) throws Exception;
	}

	private static TwoWaySqlMocker twoWaySqlMocker = null;

	public static String getMockDirective(String twoWaySqlFileName) throws IOException {
		if (!twoWaySqlFileName.startsWith("--")) {
			String twoWaySQL = TwoWaySqlExecutor.readSql(twoWaySqlFileName);
			if (twoWaySQL.startsWith(TwoWaySqlExecutor.MOCK_DIRECTIVE)) {
				return twoWaySQL.lines().findFirst().orElse("");
			}
		}
		return null;
	}

	public static java.util.List<IMendixObject> mockRetrieveByTwoWaySql(IContext context, String mockDirective, IMendixObject actionParameter, String resultEntityType) throws Exception {
		Map<String, Object> paramMap = prepareCallMocker(context, actionParameter);
		java.util.List<IMendixObject> resultList = null;
		try {
			resultList = twoWaySqlMocker.mockRetrieveByTwoWaySql(context, mockDirective, paramMap, resultEntityType);
		} finally {
			resetParameters();
		}
		return resultList;
	}

	public static java.lang.Long mockCountRowsByTwoWaySql(IContext context, String mockDirective, IMendixObject actionParameter) throws Exception {
		Map<String, Object> paramMap = prepareCallMocker(context, actionParameter);
		java.lang.Long count = null;
		try {
			count = twoWaySqlMocker.mockCountRowsByTwoWaySql(context, mockDirective, paramMap);
		} finally {
			resetParameters();
		}
		return count;
	}

	public static java.lang.Long mockUpdateByTwoWaySql(IContext context, String mockDirective, IMendixObject actionParameter) throws Exception {
		Map<String, Object> paramMap = prepareCallMocker(context, actionParameter);
		java.lang.Long count = null;
		try {
			count = twoWaySqlMocker.mockUpdateByTwoWaySql(context, mockDirective, paramMap);
		} finally {
			resetParameters();
		}
		return count;
	}

	private static HashMap<String, Object> prepareCallMocker(IContext context, IMendixObject actionParameter) {
		if (twoWaySqlMocker == null) {
			try {
				Class<?> mockerClass = Class.forName("twowaysqlmocker.implementation.TwoWaySqlMockerImpl");
				twoWaySqlMocker = (TwoWaySqlMocker)mockerClass.getDeclaredConstructor().newInstance();
			} catch (Exception e) {
				throw new MendixRuntimeException("TwoWaySqlMocker is not set and TwoWaySqlMockerImpl class is not found.", e);
			}
		}
		HashMap<String, Object> paramMap = new HashMap<String, Object>();
		paramMap.putAll(getNextParameters());
		Map<String, ? extends IMendixObjectMember<?>> momMap = actionParameter.getMembers(context);
		for (String key : momMap.keySet()) {
			// MOCKなのでmxParam2DbfluteParam()でのタイムゾーン調整は省略
			paramMap.put(key, momMap.get(key).getValue(context));
		}
		return paramMap;
	}

}
