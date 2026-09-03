-- =====================================================================
-- fault_sandbox 故障库表结构（手动执行一次）
-- 执行示例：mysql --host=<host> --user=root --password=xxx < schema.sql
-- 注意：agent/module 启动时只校验表是否存在，不会自动建库建表
-- =====================================================================

CREATE DATABASE IF NOT EXISTS fault_sandbox DEFAULT CHARSET utf8mb4;
USE fault_sandbox;

-- 表1：解析单元记录（去重入口 + 状态机）
CREATE TABLE IF NOT EXISTS t_jar_record (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  unit_type     VARCHAR(16)  NOT NULL COMMENT 'CLASSES | LIB_JAR',
  sha256        CHAR(64)     NOT NULL COMMENT '单元内容摘要',
  source_jar    VARCHAR(512) NOT NULL COMMENT '来源 jar 文件名',
  boot_jar      VARCHAR(512) NOT NULL COMMENT '所属 bootJar 完整绝对路径',
  hostname      VARCHAR(128) NOT NULL COMMENT '机器名（当前解析节点）',
  ip            VARCHAR(64)  NOT NULL COMMENT 'IP（当前解析节点）',
  class_count   INT          NULL,
  method_count  INT          NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/completed/failed',
  parsed_at     DATETIME     NULL COMMENT '解析完成时间（pending 为 NULL）',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '孤儿 pending 判定依据',
  UNIQUE KEY uk_sha256 (sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 表2：类-方法解析结果（幂等增量插入）
CREATE TABLE IF NOT EXISTS t_class_method (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  unit_id      BIGINT       NOT NULL COMMENT '→ t_jar_record.id',
  class_name   VARCHAR(256) NOT NULL COMMENT '完全限定名',
  method_name  VARCHAR(128) NOT NULL,
  method_desc  VARCHAR(256) NOT NULL COMMENT 'ASM 描述符，区分重载',
  UNIQUE KEY uk_method (unit_id, class_name, method_name, method_desc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 表3：故障注入记录（判重键 = tag + bootJar 部署路径 + 类 + 方法 + 行 + 线程 + 调用栈 + 第几次故障：
--   部署路径即应用标识——同路径 = 同一应用的集群（集群级只 kill 一个节点）；
--   不同路径 = 不同应用（即使同机、jar 内容相同），各自独立抢占独立 kill）
CREATE TABLE IF NOT EXISTS t_fault_record (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  unit_id       BIGINT       NOT NULL COMMENT '→ t_jar_record.id',
  tag           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '故障注入轮次（JVM -Dfault.tag）',
  hostname      VARCHAR(128) NOT NULL COMMENT '死亡节点机器名',
  ip            VARCHAR(64)  NOT NULL COMMENT '死亡节点 IP（观测）',
  boot_jar      VARCHAR(512) NOT NULL COMMENT '应用 bootJar 完整部署路径（判重键：路径即应用）',
  boot_jar_hash CHAR(16)     NOT NULL COMMENT 'MD5(boot_jar) 前 16 位 hex（索引键）',
  class_name    VARCHAR(256) NOT NULL,
  method_name   VARCHAR(128) NOT NULL,
  line_no       INT          NOT NULL,
  thread_name   VARCHAR(128) NOT NULL,
  fault_seq     INT          NOT NULL COMMENT '该行该线程该调用栈本轮的第几次故障（从 1 开始，上限由 inject.fault.times 决定）',
  stack_hash    CHAR(32)     NOT NULL DEFAULT '' COMMENT '调用栈摘要：MD5(stack_text) 32 位小写 hex（判重键组成部分；原文超长无法入索引）',
  stack_text    MEDIUMTEXT   NULL COMMENT '触发故障时的调用栈：已裁剪取栈入口/sandbox/本模块帧，帧格式「类.方法(文件:行)」，换行分隔',
  fault_type    VARCHAR(32)  NOT NULL DEFAULT 'KILL_PROCESS',
  occurred_at   DATETIME     NOT NULL,
  UNIQUE KEY uk_hit_node (tag, boot_jar_hash, class_name, method_name, line_no, thread_name, fault_seq, stack_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 已有库升级：为 t_fault_record 增加调用栈两列并细化唯一键
-- 适用场景：本文件此前已执行过、t_fault_record 已存在且已有数据的库。
-- 存量记录 stack_hash 统一为 ''，仍满足新唯一键——原唯一键已保证前 7 列组合唯一，
-- 追加一列后不会与存量数据冲突。执行前建议备份该表。
-- =====================================================================
-- ALTER TABLE t_fault_record
--   ADD COLUMN stack_hash CHAR(32) NOT NULL DEFAULT '' COMMENT '调用栈摘要：MD5(stack_text) 32 位小写 hex（判重键组成部分）' AFTER fault_seq,
--   ADD COLUMN stack_text MEDIUMTEXT NULL COMMENT '触发故障时的调用栈' AFTER stack_hash,
--   DROP INDEX uk_hit_node,
--   ADD UNIQUE KEY uk_hit_node (tag, boot_jar_hash, class_name, method_name, line_no, thread_name, fault_seq, stack_hash);

-- 表4：agent 自身错误记录（写入后进程将被 kill）
CREATE TABLE IF NOT EXISTS t_error_record (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  phase       VARCHAR(16)   NOT NULL COMMENT 'PARSE | DB | MOUNT',
  error_type  VARCHAR(16)   NOT NULL COMMENT 'TIMEOUT | EXCEPTION',
  message     VARCHAR(1024) NOT NULL,
  detail      TEXT          NULL COMMENT '异常堆栈',
  unit_ids    VARCHAR(256)  NULL COMMENT '涉及的表1 id 列表文本',
  boot_jar    VARCHAR(512)  NULL,
  hostname    VARCHAR(128)  NOT NULL,
  ip          VARCHAR(64)   NOT NULL,
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
