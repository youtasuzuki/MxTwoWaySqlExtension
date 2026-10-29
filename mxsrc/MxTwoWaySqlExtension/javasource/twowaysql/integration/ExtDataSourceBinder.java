package twowaysql.integration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

public class ExtDataSourceBinder {
	static Map<String, ExtDataSourceWrapper>extDataSourceMap = new ConcurrentHashMap<String, ExtDataSourceWrapper>();
	static Map<String, Long>extDataSourceOptionsMap = new ConcurrentHashMap<String, Long>();
	
	public static void putExtDataSource(String name, DataSource ds) {
		extDataSourceMap.put(name, new ExtDataSourceWrapper(name, ds));
	}
	public static Long putExtDataSourceOptions(String name, Long options) {
		return extDataSourceOptionsMap.put(name, options);
	}

	
	public static ExtDataSourceWrapper getExtDataSource(String name) {
		return extDataSourceMap.get(name);
	}
	public static Long getExtDataSourceOptions(String name) {
		Long options = extDataSourceOptionsMap.get(name);
		return options == null? 0L : options;
	}
}
