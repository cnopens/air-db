package com.mxy.air.db;

import java.io.IOException;
import java.util.Map.Entry;

import javax.sql.DataSource;

import org.elasticsearch.client.RestClient;

import com.google.common.base.Strings;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import com.mxy.air.db.config.DatacolorConfig;
import com.mxy.air.db.config.DatacolorConfig.Datasource;
import com.mxy.air.db.config.TableConfig;
import com.mxy.air.db.config.TableConfig.Column;
import com.mxy.air.db.jdbc.BasicRowProcessor;
import com.mxy.air.db.jdbc.Dialect;
import com.mxy.air.db.jdbc.RowProcessor;
import com.mxy.air.db.jdbc.dialect.ElasticsearchDialect;
import com.mxy.air.db.jdbc.processor.ElasticsearchRowProcessor;
import com.mxy.air.json.JSONObject;

/**
 * 全局配置信息类, 提供获取各种信息的静态方法
 * @author mengxiangyun
 *
 */
public class AirContext {

	private static JSONObject config;

	private static Injector injector;

	public static ThreadLocal<String> threadLocalDb = new ThreadLocal<>();

	public static void init(JSONObject config, Injector injector) {
		AirContext.config = config;
		AirContext.injector = injector;
		// 设置每个SQLSession的数据源
		for (String db : config.getObject(DatacolorConfig.DATASOURCES).keySet()) {
			DataSource dataSource = (DataSource) config.getObject(DatacolorConfig.DATASOURCES).getObject(db)
					.get(Datasource.SOURCE);
			getSqlSession(db).setDataSource(dataSource);
		}
	}

	public static JSONObject getConfig() {
		return config;
	}

	public static Injector getInjector() {
		return injector;
	}

	public static JSONObject getDbsConfig() {
		return config.getObject(DatacolorConfig.DB_TABLE_CONFIG);
	}

	public static JSONObject getDbConfig(String db) {
		JSONObject dbsConfig = getDbsConfig();
		if (dbsConfig == null) {
			return new JSONObject();
		}
		JSONObject dbConfig = dbsConfig.getObject(db);
		if (dbConfig == null) {
			return new JSONObject();
		}
		return dbConfig;
	}

	public static JSONObject getTableConfig(String table) {
		return getTableConfig(getDefaultDb(), table);
	}

	public static JSONObject getTableConfig(String db, String table) {
		JSONObject tableConfig = getDbConfig(db).getObject(table);
		if (tableConfig == null) {
			return new JSONObject();
		}
		return tableConfig;
	}

	public static JSONObject getColumnsConfig(String table) {
		return getColumnsConfig(getDefaultDb(), table);
	}

	public static JSONObject getColumnsConfig(String db, String table) {
		JSONObject tableConfig = getTableConfig(db, table);
		JSONObject columnsConfig = tableConfig.getObject(TableConfig.COLUMNS);
		if (columnsConfig == null) {
			return new JSONObject();
		}
		return columnsConfig;
	}

	public static String getColumnType(String db, String table, String column) {
		JSONObject columnsConfig = getColumnsConfig(db, table);
		JSONObject columnConfig = columnsConfig.getObject(column);
		if (columnConfig == null) {
			return null;
		} else {
			return columnConfig.getString(Column.TYPE);
		}
	}

	/**
	 * 获得2个表的关联关系, 默认为当前数据源下的表
	 * @param tableA 第一个表
	 * @param tableB 第二个表
	 * @return
	 */
	public static TableConfig.Association.Type getAssociation(String tableA, String tableB) {
		return getAssociation(getCurrentDb(), tableA, tableB);
	}

	/**
	 * 获得2个表的关联关系
	 * @param db 数据源名称
	 * @param tableA 第一个表
	 * @param tableB 第二个表
	 * @return
	 */
	public static TableConfig.Association.Type getAssociation(String db, String tableA, String tableB) {
		JSONObject tableAConfig = AirContext.getTableConfig(db, tableA);
		JSONObject tableAColumnsConfig = tableAConfig.getObject(TableConfig.COLUMNS);
		/*
		 * 1. 查看第一个表配置文件中是否配置了第二个表的关联, 循环第一个表的每个列, 找到配置了第二个表关联关系的配置
		 */
		for (Entry<String, Object> columnConfigObject : tableAColumnsConfig.entrySet()) {
			JSONObject columnConfig = (JSONObject) columnConfigObject.getValue();
			if (columnConfig.containsKey(TableConfig.Column.ASSOCIATION)) {
				JSONObject association = columnConfig.getObject(TableConfig.Column.ASSOCIATION);
				String targetTable = association.getString(TableConfig.Association.TARGET_TABLE);
				// 如果第一个表的该字段配置的关联表不是第二个表, 跳过
				if (!tableB.equals(targetTable)) {
					continue;
				}
				TableConfig.Association.Type associationType;
				String type = association.getString(TableConfig.Association.TYPE);
				if (type == null) {
					associationType = TableConfig.Association.Type.ONE_TO_ONE;
				} else {
					associationType = TableConfig.Association.Type.from(type);
				}
				return associationType;
			}
		}
		// 2. 查看第二个表表的配置文件中是否配置了第一个表的关联, 循环第二个表的每个列, 找到配置了第一个表关联关系的配置
		JSONObject tableBConfig = AirContext.getTableConfig(db, tableB);
		JSONObject tableBColumnsConfig = tableBConfig.getObject(TableConfig.COLUMNS);
		for (Entry<String, Object> joinColumnConfigObject : tableBColumnsConfig.entrySet()) {
			JSONObject joinTableColumnConfig = (JSONObject) joinColumnConfigObject.getValue();
			if (joinTableColumnConfig.containsKey(TableConfig.Column.ASSOCIATION)) {
				JSONObject association = joinTableColumnConfig.getObject(TableConfig.Column.ASSOCIATION);
				String targetTable = association.getString(TableConfig.Association.TARGET_TABLE);
				// 如果第二个表的该字段配置的关联表不是第一个表, 跳过
				if (!tableA.equals(targetTable)) {
					continue;
				}
				TableConfig.Association.Type associationType;
				String type = association.getString(TableConfig.Association.TYPE);
				if (type == null) {
					associationType = TableConfig.Association.Type.ONE_TO_ONE;
				} else {
					associationType = TableConfig.Association.Type.from(type);
				}
				/*
				 * 第二个表对第一个表的一对多反过来就是第一个表对第二个表的多对一
				 */
				switch (associationType) {
				case ONE_TO_MANY:
					associationType = TableConfig.Association.Type.MANY_TO_ONE;
					break;
				case MANY_TO_ONE:
					associationType = TableConfig.Association.Type.ONE_TO_MANY;
					break;

				default:
					break;
				}
				return associationType;
			}
		}
		return null;
	}

	public static String getDefaultDb() {
		String defaultDb = config.getString(DatacolorConfig.DEFAULT_DATASOURCE);
		if (Strings.isNullOrEmpty(defaultDb)) {
			return config.getObject(DatacolorConfig.DATASOURCES).getFirst().getKey();
		} else {
			return defaultDb;
		}
	}

	public static SQLSession getDefaultSqlSession() {
		return injector.getInstance(Key.get(SQLSession.class, Names.named(getDefaultDb())));
	}

	public static SQLSession getSqlSession(String db) {
		return injector.getInstance(Key.get(SQLSession.class, Names.named(db)));
	}
	
	public static JSONObject getAllDataSources() {
		return config.getObject(DatacolorConfig.DATASOURCES);
	}

	public static JSONObject getDataSource(String db) {
		return getAllDataSources().getObject(db);
	}

	public static Dialect getDialect(String db) {
		return (Dialect) getDataSource(db).get(Datasource.DIALECT);
	}

	public static void addDbTableConfig(String db, JSONObject dbTableConfig) {
		getDbsConfig().put(db, dbTableConfig);
	}

	/**
	 * 检查数据源和数据库表
	 * @param db
	 * @param table
	 */
	public static void check(String db, String table) {
		if (Strings.isNullOrEmpty(db) || Strings.isNullOrEmpty(table)) {
			return;
		}
		/*
		 * 跳过ES
		 */
		if (getDialect(db) instanceof ElasticsearchDialect) {
			return;
		}
		if (!getDbsConfig().containsKey(db)) {
			throw new DbException(String.format("数据源 [%s] 不存在", db));
		}
		if (!getDbConfig(db).containsKey(table)) {
			throw new DbException(String.format("数据库表 [%s] 不存在", table));
		}
	}

	enum Association {
		
		PRIMARY_TABLE, PRIMARY_COLUMN, TARGET_TABLE, TARGET_COLUMN;

	}

	public static void inState(String db) {
		threadLocalDb.set(db);
	}

	public static void outState() {
		String db = threadLocalDb.get();
		if (db != null) {
			threadLocalDb.remove();
		}
	}

	public static String getCurrentDb() {
		return threadLocalDb.get();
	}

	public static RowProcessor getRowProcessor() {
		if (isElasticsearch()) {
			return new ElasticsearchRowProcessor();
		} else {
			return new BasicRowProcessor();
		}
	}

	public static boolean isElasticsearch(String db) {
		if (db != null) {
			Dialect dialect = getDialect(db);
			if (dialect instanceof ElasticsearchDialect) {
				return true;
			}
		}
		return false;
	}

	public static boolean isElasticsearch() {
		String db = threadLocalDb.get();
		if (db != null) {
			Dialect dialect = getDialect(db);
			if (dialect instanceof ElasticsearchDialect) {
				return true;
			}
		}
		return false;
	}

	public static String getEsDb() {
		JSONObject dbsConfig = AirContext.getDbsConfig();
		String esDb = null;
		for (String db : dbsConfig.keySet()) {
			if (AirContext.isElasticsearch(db)) {
				esDb = db;
				break;
			}
		}
		return esDb;
	}

	public static RestClient getEsClient() {
		RestClient client = injector.getInstance(Key.get(RestClient.class, Names.named(getEsDb())));
		return client;
	}

	public static void closeEsClient() {
		try {
			getEsClient().close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
