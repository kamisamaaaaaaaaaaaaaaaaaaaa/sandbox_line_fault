# MEMORY

## fault-sandbox 工程（sandbox行故障，2026-08-30 创建）
- JVM-Sandbox 故障注入工具：fault-agent（premain 解析 bootJar 落 MySQL + 同步挂载 sandbox 模块 + 硬保护 kill）+ fault-module（行级 watch，表3 DB 抢占后 kill）+ 4 张表（t_jar_record/t_class_method/t_fault_record/t_error_record，库 fault_sandbox，root/root123）
- 设计要点：解析单元=classes 整体（聚合hash）+lib白名单jar；挂载命令传表1主键id；premain 同步阻塞挂载（时序确定性）；超时/异常→表4→kill 绝不放行（唯一例外 agent.enabled=false）；表2 INSERT IGNORE 幂等；表3 唯一索引集群级抢占（每行最多死一个节点）；pending 且 ip=自己+boot_jar 相同=本机中断立即重解析
- 环境事实：Linux 目标机 192.168.193.131 lys2/yi2000316lei（sudo 密码同）Ubuntu20.04 OpenJDK21；sandbox 实际目录 /home/lys2/sandbox/sandbox-module（单数）；plink 非交互模板：A:\putty\plink.exe -batch -hostkey "SHA256:iAijTOpw4SHGN6KfZ+hB6bYfLWsBsGOAN97fTJmQiQk" -pw yi2000316lei lys2@192.168.193.131
- 构建：Windows JDK21+Gradle8.12（wrapper 用腾讯镜像）；GRADLE_USER_HOME=D:\JAVA\repository；agent fat jar 内 mysql/asm/protobuf relocate；test-app=Spring Boot 2.7.18 手工 Jar task 组装 bootJar（2.x 插件不兼容 Gradle8；Zip task 无 manifest 块）
- 工程坑：PowerShell 命令行中文路径乱码（用短路径 SANDBO~2）；mysql-connector-j 坐标 com.mysql 前缀
