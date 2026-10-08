package com.aliyun.odps.agentic.storage;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
/** Plain JDBC adapter for standalone SDK users. A host can provide a transaction-aware adapter instead. */
public final class JdbcSqlDatabase implements SqlDatabase {
    private final DataSource datasource;
    public JdbcSqlDatabase(DataSource datasource) { this.datasource=Objects.requireNonNull(datasource); }
    public void execute(String sql) {
        try (var connection=datasource.getConnection(); var statement=connection.createStatement()) { statement.execute(sql); }
        catch (SQLException e) { throw failure(e); }
    }
    public int update(String sql,Object... args) {
        try (var connection=datasource.getConnection(); var statement=connection.prepareStatement(sql)) {
            bind(statement,args); return statement.executeUpdate();
        } catch (SQLException e) { throw failure(e); }
    }
    public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
        try (var connection=datasource.getConnection(); var statement=connection.prepareStatement(sql)) {
            bind(statement,args);
            try (var result=statement.executeQuery()) {
                var rows=new ArrayList<T>(); int index=0;
                while (result.next()) rows.add(mapper.map(result,index++));
                return rows;
            }
        } catch (SQLException e) { throw failure(e); }
    }
    public List<Map<String,Object>> queryForList(String sql,Object... args) {
        return query(sql,(result,row) -> {
            var metadata=result.getMetaData(); var values=new LinkedHashMap<String,Object>();
            for (int i=1;i<=metadata.getColumnCount();i++) values.put(metadata.getColumnLabel(i),result.getObject(i));
            return values;
        },args);
    }
    public <T> T queryForObject(String sql,Class<T> type,Object... args) {
        var values=query(sql,(result,row) -> {
            Object value=result.getObject(1);
            if (value instanceof Number number) {
                if (type==Integer.class) value=number.intValue();
                else if (type==Long.class) value=number.longValue();
                else if (type==Double.class) value=number.doubleValue();
            }
            return type.cast(value);
        },args);
        if (values.size()!=1) throw new IllegalStateException("Expected one SQL result, got "+values.size());
        return values.get(0);
    }
    private static void bind(PreparedStatement statement,Object[] args) throws SQLException {
        for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
    }
    private static IllegalStateException failure(SQLException e) { return new IllegalStateException("Agent state SQL operation failed",e); }
}
