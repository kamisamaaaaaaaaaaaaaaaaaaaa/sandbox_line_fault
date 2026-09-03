package cn.chinaclear.fault.common;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 表结构校验：库与 4 张表由操作者手动执行 common/src/main/resources/schema.sql 创建，
 * agent 启动时只做存在性校验，缺表则明确报错（走硬保护 kill），绝不自动建表。
 */
public final class SchemaInitializer {

    private static final String[] REQUIRED_TABLES = {
            "t_jar_record", "t_class_method", "t_fault_record", "t_error_record"
    };

    private SchemaInitializer() {
    }

    public static void checkTables(FaultConfig config) {
        try (Connection conn = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword(),
                config.jdbcDriver()).open();
             Statement st = conn.createStatement()) {
            for (String table : REQUIRED_TABLES) {
                ResultSet rs = st.executeQuery("SELECT 1 FROM " + table + " LIMIT 1");
                rs.close();
            }
            // 列级校验：表存在但缺列时，写入会在运行期抛 Unknown column（非 23 类错误），
            // 表现为进程被硬保护 kill 且原因不直观，故在建表校验阶段就明确拦下
            ResultSet rs = st.executeQuery(
                    "SELECT fault_seq, stack_hash, stack_text FROM t_fault_record LIMIT 1");
            rs.close();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "fault_sandbox schema not ready (run common/src/main/resources/schema.sql manually): "
                            + e.getMessage(), e);
        }
        FaultLogger.info("schema check OK: all 4 tables exist");
    }
}
