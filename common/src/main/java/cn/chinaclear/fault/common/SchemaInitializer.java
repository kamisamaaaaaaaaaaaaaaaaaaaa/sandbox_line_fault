package cn.chinaclear.fault.common;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** 建库 + 4 张表幂等 DDL */
public final class SchemaInitializer {

    private SchemaInitializer() {
    }

    public static void init(FaultConfig config) {
        JdbcHelper.ensureDriver();
        String url = config.jdbcUrl();
        // 先剥离库名连接建库
        try (Connection conn = DriverManager.getConnection(stripDatabase(url), config.jdbcUsername(), config.jdbcPassword());
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS fault_sandbox DEFAULT CHARSET utf8mb4");
        } catch (SQLException e) {
            throw new IllegalStateException("create database failed: " + e.getMessage(), e);
        }
        // 再建表
        try (Connection conn = DriverManager.getConnection(url, config.jdbcUsername(), config.jdbcPassword());
             Statement st = conn.createStatement()) {
            for (String ddl : DDL) {
                st.execute(ddl);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("create tables failed: " + e.getMessage(), e);
        }
        FaultLogger.info("schema initialized (database fault_sandbox + 4 tables)");
    }

    /** jdbc:mysql://host:3306/db?params -> jdbc:mysql://host:3306/?params */
    static String stripDatabase(String jdbcUrl) {
        int q = jdbcUrl.indexOf('?');
        String base = q < 0 ? jdbcUrl : jdbcUrl.substring(0, q);
        String tail = q < 0 ? "" : jdbcUrl.substring(q);
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + tail;
    }

    private static final String[] DDL = {
            "CREATE TABLE IF NOT EXISTS t_jar_record ("
                    + " id            BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " unit_type     VARCHAR(16)  NOT NULL COMMENT 'CLASSES | LIB_JAR',"
                    + " sha256        CHAR(64)     NOT NULL COMMENT '单元内容摘要',"
                    + " source_jar    VARCHAR(512) NOT NULL COMMENT '来源 jar 文件名',"
                    + " boot_jar      VARCHAR(512) NOT NULL COMMENT '所属 bootJar 完整绝对路径',"
                    + " hostname      VARCHAR(128) NOT NULL COMMENT '机器名（当前解析节点）',"
                    + " ip            VARCHAR(64)  NOT NULL COMMENT 'IP（当前解析节点）',"
                    + " class_count   INT          NULL,"
                    + " method_count  INT          NULL,"
                    + " status        VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/completed/failed',"
                    + " parsed_at     DATETIME     NULL COMMENT '解析完成时间（pending 为 NULL）',"
                    + " updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '孤儿 pending 判定依据',"
                    + " UNIQUE KEY uk_sha256 (sha256)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            "CREATE TABLE IF NOT EXISTS t_class_method ("
                    + " id           BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " unit_id      BIGINT       NOT NULL COMMENT 't_jar_record.id',"
                    + " class_name   VARCHAR(256) NOT NULL COMMENT '完全限定名',"
                    + " method_name  VARCHAR(128) NOT NULL,"
                    + " method_desc  VARCHAR(256) NOT NULL COMMENT 'ASM 描述符，区分重载',"
                    + " UNIQUE KEY uk_method (unit_id, class_name, method_name, method_desc)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            "CREATE TABLE IF NOT EXISTS t_fault_record ("
                    + " id           BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " unit_id      BIGINT       NOT NULL COMMENT 't_jar_record.id',"
                    + " hostname     VARCHAR(128) NOT NULL COMMENT '死亡节点机器名',"
                    + " ip           VARCHAR(64)  NOT NULL,"
                    + " class_name   VARCHAR(256) NOT NULL,"
                    + " method_name  VARCHAR(128) NOT NULL,"
                    + " line_no      INT          NOT NULL,"
                    + " thread_name  VARCHAR(128) NOT NULL,"
                    + " fault_type   VARCHAR(32)  NOT NULL DEFAULT 'KILL_PROCESS',"
                    + " occurred_at  DATETIME     NOT NULL,"
                    + " UNIQUE KEY uk_hit (unit_id, class_name, method_name, line_no)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            "CREATE TABLE IF NOT EXISTS t_error_record ("
                    + " id          BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " phase       VARCHAR(16)   NOT NULL COMMENT 'PARSE | DB | MOUNT',"
                    + " error_type  VARCHAR(16)   NOT NULL COMMENT 'TIMEOUT | EXCEPTION',"
                    + " message     VARCHAR(1024) NOT NULL,"
                    + " detail      TEXT          NULL COMMENT '异常堆栈',"
                    + " unit_ids    VARCHAR(256)  NULL COMMENT '涉及的表1 id 列表文本',"
                    + " boot_jar    VARCHAR(512)  NULL,"
                    + " hostname    VARCHAR(128)  NOT NULL,"
                    + " ip          VARCHAR(64)   NOT NULL,"
                    + " created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    };
}
