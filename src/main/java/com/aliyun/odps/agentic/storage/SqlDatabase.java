package com.aliyun.odps.agentic.storage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
/** Database access port for durable agent state. Implementations retain their connection/transaction policy. */
public interface SqlDatabase {
    @FunctionalInterface interface RowMapper<T> { T map(ResultSet result, int row) throws SQLException; }
    void execute(String sql);
    int update(String sql, Object... arguments);
    <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments);
    List<Map<String,Object>> queryForList(String sql, Object... arguments);
    <T> T queryForObject(String sql, Class<T> type, Object... arguments);
}
