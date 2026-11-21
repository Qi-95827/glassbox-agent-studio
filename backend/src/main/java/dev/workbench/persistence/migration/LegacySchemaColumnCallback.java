package dev.workbench.persistence.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

/**
 * 在 Flyway 应用 V1 之前补齐旧结构缺失的列。
 *
 * <p>V1 对已存在的表使用 {@code CREATE TABLE IF NOT EXISTS} 跳过建表，但随后会添加依赖新列的外键。
 * 这里只做最小、幂等的列补齐，不修改已发布迁移文件的校验和；V2 继续负责其余列、约束和数据回填。
 */
public class LegacySchemaColumnCallback implements Callback {
  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.BEFORE_MIGRATE;
  }

  @Override
  public boolean canHandleInTransaction(Event event, Context context) {
    return true;
  }

  @Override
  public String getCallbackName() {
    return "legacy-schema-columns";
  }

  @Override
  public void handle(Event event, Context context) {
    Connection connection = context.getConnection();
    try {
      addColumnIfTableExists(connection, "plan_nodes", "plan_version_id", "VARCHAR(36)");
      addColumnIfTableExists(connection, "workflow_plans", "source_checkpoint_id", "VARCHAR(36)");
    } catch (SQLException e) {
      throw new IllegalStateException("旧库兼容列补齐失败，已停止迁移。", e);
    }
  }

  private static void addColumnIfTableExists(
      Connection connection, String table, String column, String type) throws SQLException {
    if (!tableExists(connection, table) || columnExists(connection, table, column)) return;
    try (Statement statement = connection.createStatement()) {
      statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }
  }

  private static boolean tableExists(Connection connection, String table) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    for (String candidate : names(table)) {
      try (ResultSet rows =
          metadata.getTables(connection.getCatalog(), null, candidate, new String[] {"TABLE"})) {
        if (rows.next()) return true;
      }
    }
    return queryExists(
        connection,
        "SELECT 1 FROM information_schema.tables WHERE lower(table_name)=lower(?)",
        table);
  }

  private static boolean columnExists(Connection connection, String table, String column)
      throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    for (String tableName : names(table)) {
      for (String columnName : names(column)) {
        try (ResultSet rows =
            metadata.getColumns(
                connection.getCatalog(), null, tableName, columnName)) {
          if (rows.next()) return true;
        }
      }
    }
    return queryExists(
        connection,
        "SELECT 1 FROM information_schema.columns WHERE lower(table_name)=lower(?) AND lower(column_name)=lower(?)",
        table,
        column);
  }

  private static boolean queryExists(Connection connection, String sql, String... values)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static String[] names(String value) {
    return new String[] {value, value.toUpperCase(Locale.ROOT), value.toLowerCase(Locale.ROOT)};
  }
}
