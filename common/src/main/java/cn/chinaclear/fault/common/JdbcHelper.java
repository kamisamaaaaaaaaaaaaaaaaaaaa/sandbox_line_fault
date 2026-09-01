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
     * 唯一键冲突判定：SQLState 23 类（ANSI SQL 标准的约束违反类，MySQL 23000 / PG 23505 等均属此类）
     * 兼兜底驱动抛出的 JDBC 标准异常子类。
     * 前提：本工具的表无外键、插入参数由代码构造保证非 null，23 类实际只可能来自唯一键冲突。
     */
    public static boolean isConstraintConflict(SQLException e) {
        if (e instanceof java.sql.SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }

    /**
     * 裸 INSERT（单行）：唯一键冲突返回 false（调用方语义"已存在"放行），其他 SQLException 一律上抛走硬保护。
     * 插入成功且有 keyHolder 时回调自增主键。
     */
    public boolean executeInsert(String sql, Object[] params, KeyHolder keyHolder) {
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
            if (isConstraintConflict(e)) {
                return false;
            }
            throw new IllegalStateException("insert failed: " + sql + " - " + e.getMessage(), e);
        }
    }

    /**
     * 逐行裸 INSERT（单连接事务，全部行处理完一次性提交——无部分提交的中间状态）：
     * 唯一键冲突（SQLState 23 类）的行跳过（该行已存在），其他任何 SQLException
     * 立即上抛并由本方法回滚整个事务（调用方走硬保护）。
     */
    public void batchInsertSkipConflict(String sql, List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        try (Connection conn = open()) {
            boolean old = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Object[] row : rows) {
                    bind(ps, row);
                    try {
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        if (!isConstraintConflict(e)) {
                            throw e;    // 非 23 类错误：回滚整个事务后上抛（走硬保护）
                        }
                        // 唯一键冲突：该行已存在（他节点/上次解析已写入），跳过
                    }
                }
                conn.commit();    // 全部行处理完毕一次性提交
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
