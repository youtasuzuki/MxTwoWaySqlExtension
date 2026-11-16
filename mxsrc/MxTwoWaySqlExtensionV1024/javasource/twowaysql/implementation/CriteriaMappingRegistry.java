package twowaysql.implementation;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.MendixRuntimeException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;
import com.mendix.thirdparty.org.json.JSONArray;
import com.mendix.thirdparty.org.json.JSONObject;

import twowaysql.integration.ExtDataSourceBinder;

public class CriteriaMappingRegistry {
	public static final ILogNode logger = Core.getLogger("TwoWaySql");
	private static final CriteriaMappingRegistry instance = new CriteriaMappingRegistry();
	private final Set<MappingKey> registeredMappings = ConcurrentHashMap.newKeySet();

	private final Map<String, MockMapping> mockMappingMap = new ConcurrentHashMap<>();
	private volatile boolean isMockConfigLoaded = false;

	private CriteriaMappingRegistry() {
	}

	public static CriteriaMappingRegistry getInstance() {
		return instance;
	}

	public List<MappingKey> getAllMappings() {
		return new ArrayList<>(registeredMappings);
	}

	/**
	 * Register mappings for data sources, tables, and DTO/Param types in AfterStartUp.
	 */
	public boolean registerMapping(String dataSourceName, String tableName, String paramOrDtoType) {
		if (dataSourceName == null || tableName == null || paramOrDtoType == null) {
			return false;
		}
		MappingKey key = new MappingKey(dataSourceName, tableName, paramOrDtoType);
		return registeredMappings.add(key);
	}

	/**
	 * Verify that the provided data source, table, and DTO/Param types are correctly registered.
	 */
	public boolean isValidMapping(String dataSourceName, String tableName, String dtoType) {
		if (dataSourceName == null || tableName == null || dtoType == null) {
			return false;
		}
		MappingKey key = new MappingKey(dataSourceName, tableName, dtoType);
		return registeredMappings.contains(key);
	}

	// The class for holding keys to maintain the uniqueness of combinations internally.
	public class MappingKey {
		private final String dataSourceName;
		private final String tableName;
		private final String dtoType;

		public MappingKey(String dataSourceName, String tableName, String dtoType) {
			this.dataSourceName = dataSourceName.trim();
			this.tableName = tableName.trim().toLowerCase(); // Normalize table names to lowercase.
			this.dtoType = dtoType.trim();
		}

		public String getDataSourceName() {
			return dataSourceName;
		}

		public String getTableName() {
			return tableName;
		}

		public String getDtoType() {
			return dtoType;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;
			if (o == null || getClass() != o.getClass())
				return false;
			MappingKey that = (MappingKey) o;
			return Objects.equals(dataSourceName, that.dataSourceName) &&
					Objects.equals(tableName, that.tableName) &&
					Objects.equals(dtoType, that.dtoType);
		}

		@Override
		public int hashCode() {
			return Objects.hash(dataSourceName, tableName, dtoType);
		}
	}

	// Key: "DS name.table name (lowercase)" ➔ Value: TableMetadata (exact table name + column map)
	private final Map<String, TableMetadata> tableCache = new ConcurrentHashMap<>();

	/**
	 * Retrieve the exact table name from the database (automatically load if not cached).
	 */
	public String resolveTableName(IContext context, String dataSourceName, String rawTableName) {
		if (dataSourceName == null || rawTableName == null)
			return rawTableName;

		TableMetadata meta = getOrLoadTableMetadata(context, dataSourceName, rawTableName);
		return meta != null && meta.actualTableName != null ? meta.actualTableName : rawTableName;
	}

	/**
	 * Retrieve the exact column name from the database (automatically load if not cached).
	 */
	public String resolveColumnName(IContext context, String dataSourceName, String rawTableName, String attrName) {
		if (dataSourceName == null || rawTableName == null || attrName == null)
			return attrName;

		TableMetadata meta = getOrLoadTableMetadata(context, dataSourceName, rawTableName);
		if (meta != null) {
			String actualCol = meta.columnMap.get(attrName.toLowerCase());
			if (actualCol != null) {
				return actualCol;
			}
		}
		return attrName; // If no match is found, fall back to the original attribute name.
	}

	/**
	 * Cache retrieval or automatic loading from JDBC (Lazy Load)
	 */
	private TableMetadata getOrLoadTableMetadata(IContext context, String dataSourceName, String rawTableName) {
		String cacheKey = (dataSourceName + "." + rawTableName).trim().toLowerCase();

		return tableCache.computeIfAbsent(cacheKey, key -> {
			TableMetadata meta = new TableMetadata();
			try (Connection conn = ExtDataSourceBinder.getExtDataSource(dataSourceName).getConnection(context)) {
				DatabaseMetaData dbMeta = conn.getMetaData();

				try (ResultSet rs = dbMeta.getTables(null, null, rawTableName, new String[] { "TABLE", "VIEW" })) {
					if (rs.next()) {
						meta.actualTableName = rs.getString("TABLE_NAME");
					}
				}
				if (meta.actualTableName == null) {
					try (ResultSet rs = dbMeta.getTables(null, null, rawTableName.toUpperCase(),
							new String[] { "TABLE", "VIEW" })) {
						if (rs.next()) {
							meta.actualTableName = rs.getString("TABLE_NAME");
						}
					}
				}
				if (meta.actualTableName == null) {
					try (ResultSet rs = dbMeta.getTables(null, null, rawTableName.toLowerCase(),
							new String[] { "TABLE", "VIEW" })) {
						if (rs.next()) {
							meta.actualTableName = rs.getString("TABLE_NAME");
						}
					}
				}
				if (meta.actualTableName == null) {
					meta.actualTableName = rawTableName; // Fall back to the input value if not found.
				}

				// 2. Retrieving the list of column names
				try (ResultSet rs = dbMeta.getColumns(null, null, meta.actualTableName, null)) {
					while (rs.next()) {
						String colName = rs.getString("COLUMN_NAME");
						meta.columnMap.put(colName.toLowerCase(), colName);
					}
				}
			} catch (Exception e) {
				meta.actualTableName = rawTableName;
			}
			return meta;
		});
	}

	// Class for internal cache
	private static class TableMetadata {
		String actualTableName;
		Map<String, String> columnMap = new HashMap<>();
	}

	public static boolean shouldSkipAttribute(IMetaPrimitive primitive) {
		String attrName = primitive.getName();
		if (attrName.startsWith("_")) {
			return true;
		}
		if (primitive.isVirtual()) {
			return true;
		}
		return "id".equalsIgnoreCase(attrName) ||
				"createdDate".equalsIgnoreCase(attrName) ||
				"changedDate".equalsIgnoreCase(attrName) ||
				"owner".equalsIgnoreCase(attrName) ||
				"changedBy".equalsIgnoreCase(attrName);
	}

	// Single mapping test
	public CriteriaTestResult testSingleMapping(IContext context, String dataSourceName, String tableName,
			String paramOrDtoType) {
		CriteriaTestResult result = new CriteriaTestResult(dataSourceName, tableName, paramOrDtoType);

		try {
			// 1. Verifying the existence of the Mendix MetaObject
			IMetaObject metaObject = Core.getMetaObject(paramOrDtoType);
			if (metaObject == null) {
				throw new MendixRuntimeException("Entity type '" + paramOrDtoType + "' is not found in Mendix model.");
			}

			// 2. Retrieving DB table metadata (referencing via the reused caching mechanism)
			TableMetadata tableMeta = getOrLoadTableMetadata(context, dataSourceName, tableName);
			if (tableMeta == null || tableMeta.actualTableName == null) {
				throw new MendixRuntimeException(
						"Table '" + tableName + "' does not exist in DataSource '" + dataSourceName + "'.");
			}

			// 3. Check if the entity's attributes exist as database columns.
			List<String> missingColumns = new ArrayList<>();
			for (IMetaPrimitive primitive : metaObject.getMetaPrimitives()) {
				if (shouldSkipAttribute(primitive)) {
					continue;
				}
				String attrName = primitive.getName();
				String matchedColumn = tableMeta.columnMap.get(attrName.toLowerCase());
				if (matchedColumn == null) {
					missingColumns.add(attrName);
				}
			}
			if (!missingColumns.isEmpty()) {
				throw new MendixRuntimeException(
						"Attributes not found in DB table: " + String.join(", ", missingColumns));
			}
			result.setSuccess(true);
		} catch (Exception e) {
			result.setSuccess(false);
			result.setErrorMessage(e.getMessage());
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			result.setStackTrace(sw.toString());
		}

		return result;
	}

	public class CriteriaTestResult {
		String dataSourceName;
		String tableName;
		String paramOrDtoType;
		Boolean isSuccess;
		String errorMessage;
		String stackTrace;

		public CriteriaTestResult(String _dataSourceName, String _tableName, String _paramOrDtoType) {
			this.dataSourceName = _dataSourceName;
			this.tableName = _tableName;
			this.paramOrDtoType = _paramOrDtoType;
		}

		public String getDataSourceName() {
			return dataSourceName;
		}

		public void setDataSourceName(String dataSourceName) {
			this.dataSourceName = dataSourceName;
		}

		public String getTableName() {
			return tableName;
		}

		public void setTableName(String tableName) {
			this.tableName = tableName;
		}

		public String getParamOrDtoType() {
			return paramOrDtoType;
		}

		public void setParamOrDtoType(String paramOrDtoType) {
			this.paramOrDtoType = paramOrDtoType;
		}

		public Boolean isSuccess() {
			return isSuccess;
		}

		public void setSuccess(Boolean isSuccess) {
			this.isSuccess = isSuccess;
		}

		public String getErrorMessage() {
			return errorMessage;
		}

		public void setErrorMessage(String errorMessage) {
			this.errorMessage = errorMessage;
		}

		public String getStackTrace() {
			return stackTrace;
		}

		public void setStackTrace(String stackTrace) {
			this.stackTrace = stackTrace;
		}

	}

	// Mock mapping configuration information
	public static class MockMapping {
		private final String originalDataSourceName;
		private final String originalTableName;
		private final String paramOrDtoType;
		private final String excelPath;
		private final String sheetName;

		public MockMapping(String originalDataSourceName, String originalTableName, String paramOrDtoType,
				String excelPath,
				String sheetName) {
			this.originalDataSourceName = originalDataSourceName;
			this.originalTableName = originalTableName;
			this.paramOrDtoType = paramOrDtoType;
			this.excelPath = excelPath;
			this.sheetName = sheetName;
		}

		public String getOriginalDataSourceName() {
			return originalDataSourceName;
		}

		public String getOriginalTableName() {
			return originalTableName;
		}

		public String getParamOrDtoType() {
			return paramOrDtoType;
		}

		public String getExcelPath() {
			return excelPath;
		}

		public String getSheetName() {
			return sheetName;
		}

		/**
		 * Generate a consolidated table name to avoid conflicts within H2 (e.g., SALESDB_EMPLOYEE3)
		 */
		public String getH2TableName() {
			return (originalDataSourceName + "_" + originalTableName).toUpperCase();
		}
	}

	/**
	 * Lazy-load criteria-mocks.json
	 */
	public void loadMockConfigIfNeeded() {
		if (isMockConfigLoaded)
			return;

		synchronized (this) {
			if (isMockConfigLoaded)
				return;

			File resourcesDir = Core.getConfiguration().getResourcesPath();
			File mockFile = new File(resourcesDir + File.separator + "sql" + File.separator + "criteria-mocks.json");

			if (mockFile.exists()) {
				try {
					String jsonContent = new String(Files.readAllBytes(mockFile.toPath()), "UTF-8");
					JSONObject root = new JSONObject(jsonContent);
					if (root.optBoolean("enabled", false)) {
						JSONArray mocks = root.optJSONArray("mocks");
						if (mocks != null) {
							for (int i = 0; i < mocks.length(); i++) {
								JSONObject mock = mocks.getJSONObject(i);
								String dsName = mock.getString("dataSourceName");
								String tableName = mock.getString("tableName");
								String dtoType = mock.getString("paramOrDtoType");
								String excelPath = mock.getString("excelPath");
								String sheetName = mock.optString("sheetName", tableName);
								String key = buildKey(dsName, tableName, dtoType);
								mockMappingMap.put(key, new MockMapping(dsName, tableName, dtoType, excelPath, sheetName));
							}
						}
					}
					logger.info("Loaded criteria-mocks.json (" + mockMappingMap.size()
							+ " entries of mocks) successfully.");
				} catch (Exception e) {
					logger.error("Failed to parse criteria-mocks.json", e);
				}
			} else {
				logger.info("criteria-mocks.json not found in resources/sql/; skipping mock configuration loading.");
			}
			isMockConfigLoaded = true;
		}
	}

	public MockMapping getMockMapping(String dataSourceName, String tableName, String dtoType) {
		loadMockConfigIfNeeded();
		if (mockMappingMap.isEmpty())
			return null;

		String key = buildKey(dataSourceName, tableName, dtoType);
		return mockMappingMap.get(key);
	}

	private String buildKey(String dataSourceName, String tableName, String dtoType) {
		return (dataSourceName + "." + tableName + "." + dtoType).trim().toLowerCase();
	}

}
