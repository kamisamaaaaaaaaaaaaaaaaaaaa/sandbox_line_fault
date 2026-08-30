package cn.chinaclear.fault.common;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 JDBC 封装：每次操作独立连接（premain / 模块命令均为低频场景，不引入连接池）。
 * 注意：mysql driver 在 agent fat jar 内被 relocate，故这里按候选类名显式加载。
 */
public final class JdbcHelper {

    private final String url;
    private final String username;
    private final String password;

    public JdbcHelper(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    private static volatile boolean driverReady = false;

    /** 显式加载 mysql driver：兼容普通包名与 agent fat jar 内 relocate 后的包名 */
    public static void ensureDriver() {
        if (driverReady) {
            return;
        }
        synchronized (JdbcHelper.class) {
            if (driverReady) {
                return;
            }
            String[] candidates = {
                    "com.mysql.cj.jdbc.Driver",
                    "cn.chinaclear.fault.shaded.mysql.cj.jdbc.Driver"
            };
            for (String cls : candidates) {
                try {
                    Class.forName(cls);
                    driverReady = true;
                    return;
                } catch (Throwable ignore) {
                    // try next
                }
            }
            // 均未命中时交由 DriverManager SPI 自动发现（driver 可能在应用 classpath 上）
            driverReady = true;
        }
    }

    public Connection open() {
        try {
            ensureDriver();
            return DriverManager.getConnection(url, username, password);
        } catch (SQLException e) {
            throw new IllegalStateException("open db connection failed: " + e.getMessage(), e);
        }
    }

    public <T> List<T> query(String sql, Object[] params, RowMapper<T> mapper) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql + " - " + e.getMessage(), e);
        }
    }

    public int execute(String sql, Object[] params) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("execute failed: " + sql + " - " + e.getMessage(), e);
        }
    }

    /**
     * INSERT IGNORE：返回是否真正插入（false = 唯一键冲突被忽略）。
     * keyHolder 可为空，插入成功时回调自增主键。
     */
    public boolean executeInsertIgnore(String sql, Object[] params, KeyHolder keyHolder) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            int n = ps.executeUpdate();
            if (n > 0 && keyHolder != null) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        keyHolder.accept(rs.getLong(1));
                    }
                }
            }
            return n > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("insert failed: " + sql + " - " + e.getMessage(), e);
        }
    }

    /** 批量 INSERT IGNORE：单连接事务内分批提交 */
    public void batchInsertIgnore(String sql, List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        try (Connection conn = open()) {
            boolean old = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int count = 0;
                for (Object[] row : rows) {
                    bind(ps, row);
                    ps.addBatch();
                    if (++count % 1000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
                conn.commit();
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ignore) {
                    // rollback failure is irrelevant
                }
                throw e instanceof RuntimeException ? (RuntimeException) e
                        : new IllegalStateException("batch insert failed: " + e.getMessage(), e);
            } finally {
                try {
                    conn.setAutoCommit(old);
                } catch (SQLException ignore) {
                    // restore failure is irrelevant
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("batch insert failed: " + e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public interface KeyHolder {
        void accept(long id);
    }
}
