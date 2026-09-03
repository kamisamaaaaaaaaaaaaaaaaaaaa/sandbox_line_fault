package cn.chinaclear.fault.common;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 轻量 JDBC 封装：每次操作独立连接（premain / 模块命令均为低频场景，不引入连接池）。
 * 驱动类名由配置 jdbc.driver 指定（换数据库时改这一项，见 README 的"更换数据库"章节）。
 *
 * 连接一律由本类自己持有的 Driver 实例建立（driver.connect），不经过 DriverManager：
 * DriverManager 是 JVM 全局单例，按"注册顺序 + 对调用方 classloader 是否可见"挑驱动，会让模块
 * 拿到 agent fat jar 里的驱动，从而与 agent 的 jar 文件物理共命运（agent jar 按需读类失败会直接
 * 打断模块的 JDBC 调用）。显式持有实例后，模块用模块 jar 里的驱动、agent 用 agent jar 里的驱动。
 */
public final class JdbcHelper {

    private static final Object DRIVER_LOCK = new Object();

    /** 已解析的驱动实例，及其对应的配置值（配置值变化则重新解析） */
    private static volatile Driver driver;
    private static volatile String driverResolvedFor;

    private final String url;
    private final String username;
    private final String password;
    private final String driverClassName;

    public JdbcHelper(String url, String username, String password, String driverClassName) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.driverClassName = driverClassName;
    }

    /**
     * 解析并持有驱动实例：配置值即唯一的驱动类名，加载失败直接抛错（走硬保护），无内置兜底。
     *
     * agent 与 module 各自持有独立的 common 类与静态状态（sandbox 模块类加载器为子优先加载），
     * `Class.forName` 用的是本类的定义类加载器，因此两侧各自解析、互不干扰。
     *
     * 注意：agent fat jar 内的第三方依赖被 relocate（见 fault-agent/build.gradle），jar 中**不存在**
     * 原包名的驱动类，故 agent 侧 config.yml 的 jdbc.driver 必须填 relocate 后的类名；module 未做
     * relocate，填驱动的标准类名即可。详见 README 的"更换数据库"一节。
     */
    private Driver resolveDriver() {
        if (driverClassName == null || driverClassName.trim().isEmpty()) {
            throw new IllegalStateException("required config missing: jdbc.driver");
        }
        Driver cached = driver;
        if (cached != null && driverClassName.equals(driverResolvedFor)) {
            return cached;
        }
        synchronized (DRIVER_LOCK) {
            cached = driver;
            if (cached != null && driverClassName.equals(driverResolvedFor)) {
                return cached;
            }
            try {
                Driver d = (Driver) Class.forName(driverClassName).getDeclaredConstructor().newInstance();
                driver = d;
                driverResolvedFor = driverClassName;
                FaultLogger.info("jdbc driver resolved: " + driverClassName);
                return d;
            } catch (Throwable t) {
                throw new IllegalStateException("jdbc driver load failed: " + driverClassName
                        + " -> " + t + "（运行在 agent fat jar 内时，依赖已被 relocate，jdbc.driver 需填 relocate 后的类名）", t);
            }
        }
    }

    /** 建连接：用本类持有的驱动实例直连，不经过 DriverManager 的全局扫描 */
    public Connection open() {
        Driver d = resolveDriver();
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        try {
            Connection conn = d.connect(url, props);
            if (conn == null) {
                // 驱动不认这个 URL：显式报错，避免与"连接失败"混淆
                throw new IllegalStateException("driver " + d.getClass().getName() + " does not accept url: " + url);
            }
            return conn;
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
     * 逐行裸 INSERT（每行独立 autocommit 立即提交）：唯一键冲突（SQLState 23 类）的行跳过
     * （该行已存在），其他任何 SQLException 上抛由调用方走硬保护。
     *
     * 为什么不用跨行事务：表2 靠唯一索引幂等收敛，任何部分写入都是合法中间态（重解析自动补齐），
     * 无需事务原子性；而多语句长事务会把重复键 S 锁（RR 下含 next-key gap 锁）累积到批尾，
     * 多节点并发解析同一单元时构成锁等待环（实测发生过 InnoDB 死锁）。
     * 逐行提交后锁持有毫秒级，单语句事务结构上不可能形成死锁环。
     */
    public void batchInsertSkipConflict(String sql, List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        try (Connection conn = open()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Object[] row : rows) {
                    bind(ps, row);
                    try {
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        if (!isConstraintConflict(e)) {
                            throw e;    // 非 23 类错误：上抛（调用方走硬保护）
                        }
                        // 唯一键冲突：该行已存在（他节点/上次解析已写入），跳过
                    }
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
