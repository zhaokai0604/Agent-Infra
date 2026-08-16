-- 用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
  `user_id` int(11) NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(64) NOT NULL COMMENT '用户名',
  `password` varchar(128) NOT NULL COMMENT '密码',
  `role` int(11) DEFAULT '0' COMMENT '角色：0-普通用户，1-管理员',
  `wechat_userid` varchar(128) DEFAULT NULL COMMENT '企业微信用户ID，用于推送',
  `email` varchar(128) DEFAULT NULL COMMENT '邮箱地址，用于推送',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- Default admin seed: 12345678 / 12345678
-- Idempotent insert for repeated schema imports
INSERT INTO `sys_user` (`username`, `password`, `role`, `email`, `create_time`, `update_time`)
SELECT
  '12345678',
  '$2b$10$z0csJ1KrtOcpvymI.vKWpuocS6HhZ8w9JhGoYdFxLlHAz9wmwnPN6',
  1,
  NULL,
  NOW(),
  NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_user` WHERE `username` = '12345678'
);

-- 角色表
CREATE TABLE IF NOT EXISTS `sys_role` (
  `role_id` int(11) NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_name` varchar(64) NOT NULL COMMENT '角色名称',
  `role_desc` varchar(255) DEFAULT NULL COMMENT '角色描述',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`role_id`),
  UNIQUE KEY `uk_role_name` (`role_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统角色表';

-- 权限表
CREATE TABLE IF NOT EXISTS `sys_permission` (
  `permission_id` int(11) NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  `permission_name` varchar(64) NOT NULL COMMENT '权限名称',
  `permission_code` varchar(64) NOT NULL COMMENT '权限代码',
  `parent_id` int(11) DEFAULT NULL COMMENT '父权限ID',
  `description` varchar(255) DEFAULT NULL COMMENT '权限描述',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`permission_id`),
  UNIQUE KEY `uk_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统权限表';

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
  `role_id` int(11) NOT NULL COMMENT '角色ID',
  `permission_id` int(11) NOT NULL COMMENT '权限ID',
  PRIMARY KEY (`role_id`,`permission_id`),
  KEY `idx_permission_id` (`permission_id`),
  CONSTRAINT `fk_role_permission_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`role_id`) ON DELETE CASCADE,
  CONSTRAINT `fk_role_permission_permission` FOREIGN KEY (`permission_id`) REFERENCES `sys_permission` (`permission_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

-- 主任务表
CREATE TABLE IF NOT EXISTS `log_analysis_task` (
  `task_id` varchar(64) NOT NULL COMMENT '任务ID',
  `user_id` int(11) DEFAULT NULL COMMENT '创建用户ID',
  `file_name` varchar(255)  COMMENT '文件名',
  `status` varchar(32) DEFAULT NULL COMMENT '状态: PENDING, PROCESSING, COMPLETED, FAILED',
  `progress` int(11) DEFAULT '0' COMMENT '进度',
  `current_step` varchar(255) DEFAULT NULL COMMENT '当前步骤',
  `error_msg` text COMMENT '任务级别的错误信息',
  `ai_diagnosis` text COMMENT 'AI诊断结果',
  `total_logs` int(11) DEFAULT '0' COMMENT '总日志数',
  `anomaly_count` int(11) DEFAULT '0' COMMENT '异常日志数',
  `anomaly_rate` double DEFAULT '0' COMMENT '异常率',
  `cost_time` bigint(20) DEFAULT '0' COMMENT '耗时(ms)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`task_id`),
  KEY `idx_user_id` (`user_id`),
  CONSTRAINT `fk_task_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`user_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日志分析任务表';

-- 任务详情表（存储具体的异常日志或解析结果）
CREATE TABLE IF NOT EXISTS `log_analysis_detail` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `task_id` varchar(64) NOT NULL COMMENT '关联任务ID',
  `log_time` varchar(64) DEFAULT NULL COMMENT '日志时间',
  `severity` varchar(32) DEFAULT NULL COMMENT '日志等级',
  `protocol` varchar(64) DEFAULT NULL COMMENT '协议类型',
  `pid` varchar(32) DEFAULT NULL COMMENT 'PID/EventID',
  `is_anomaly` tinyint(1) DEFAULT '0' COMMENT '是否异常',
  `anomaly_score` double DEFAULT '0' COMMENT '异常得分',
  `anomaly_reasons` text COMMENT '异常原因',
  `desensitized_log` text COMMENT '脱敏后的日志内容',
  `template_id` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  CONSTRAINT `fk_detail_task` FOREIGN KEY (`task_id`) REFERENCES `log_analysis_task` (`task_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日志分析详情表';

-- 告警规则表
-- 先删除旧表（如果存在）
DROP TABLE IF EXISTS `alarm_rule`;

-- 重新创建alarm_rule表
CREATE TABLE IF NOT EXISTS `alarm_rule` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  `name` varchar(100) NOT NULL COMMENT '规则名称',
  `description` varchar(500) DEFAULT NULL COMMENT '规则描述',
  `rule_type` varchar(20) NOT NULL COMMENT '规则类型：KEYWORD, PATTERN, THRESHOLD, COMBINATION',
  `rule_expression` text NOT NULL COMMENT '规则表达式',
  `severity` varchar(20) NOT NULL COMMENT '告警级别：FATAL, ERROR, WARNING, INFO',
  `push_channels` varchar(50) NOT NULL COMMENT '推送渠道：WECHAT, EMAIL, BOTH',
  `enabled` tinyint(1) DEFAULT '1' COMMENT '是否启用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则表';

-- 插入测试数据
INSERT INTO `alarm_rule` (`name`, `description`, `rule_type`, `rule_expression`, `severity`, `push_channels`, `enabled`) 
VALUES 
('测试告警规则', '测试告警功能，当日志中包含错误关键词时触发', 'KEYWORD', 'error,exception,fail', 'ERROR', 'BOTH', 1),
('警告级别告警规则', '当日志中包含警告关键词时触发', 'KEYWORD', 'warn,warning,alert', 'WARNING', 'EMAIL', 1),
('致命错误告警规则', '当日志中包含致命错误时触发', 'KEYWORD', 'fatal,critical,panic', 'FATAL', 'BOTH', 1);

-- 告警表
CREATE TABLE IF NOT EXISTS `log_alarm` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '告警ID',
  `alarm_id` varchar(64) NOT NULL COMMENT '告警唯一标识',
  `task_id` varchar(64) NOT NULL COMMENT '关联任务ID',
  `level` varchar(32) NOT NULL COMMENT '告警级别：FATAL, ERROR, WARNING, INFO',
  `root_cause` text COMMENT '根因分析',
  `solution` text COMMENT '解决方案',
  `log_content` text COMMENT '关联的日志内容',
  `push_status` varchar(32) DEFAULT 'PENDING' COMMENT '推送状态：PENDING, SUCCESS, FAILED, SKIPPED, RETRYING',
  `lifecycle_status` varchar(32) DEFAULT 'NEW' COMMENT '生命周期状态：NEW, ACKNOWLEDGED, HANDLED, CLOSED',
  `ack_by` varchar(64) DEFAULT NULL COMMENT '确认人',
  `ack_time` datetime DEFAULT NULL COMMENT '确认时间',
  `handled_by` varchar(64) DEFAULT NULL COMMENT '处理人',
  `handled_time` datetime DEFAULT NULL COMMENT '处理时间',
  `closed_time` datetime DEFAULT NULL COMMENT '关闭时间',
  `escalation_level` int(11) DEFAULT '0' COMMENT '升级等级',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alarm_id` (`alarm_id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_level` (`level`),
  KEY `idx_push_status` (`push_status`),
  KEY `idx_lifecycle_status` (`lifecycle_status`),
  KEY `idx_create_time` (`create_time`),
  CONSTRAINT `fk_alarm_task` FOREIGN KEY (`task_id`) REFERENCES `log_analysis_task` (`task_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日志告警表';

-- 模型评估记录表
CREATE TABLE IF NOT EXISTS `model_evaluation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `model_version` varchar(64) NOT NULL COMMENT '模型版本',
  `sample_size` int(11) NOT NULL COMMENT '样本量',
  `accuracy` double DEFAULT NULL,
  `precision_score` double DEFAULT NULL,
  `recall_score` double DEFAULT NULL,
  `f1_score` double DEFAULT NULL,
  `roc_auc` double DEFAULT NULL,
  `pr_auc` double DEFAULT NULL,
  `confusion_matrix` text COMMENT '混淆矩阵JSON',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_model_version` (`model_version`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型评估记录表';

-- 决策留痕表
CREATE TABLE IF NOT EXISTS `decision_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `decision_id` varchar(64) NOT NULL COMMENT '决策ID',
  `engine_type` varchar(32) NOT NULL COMMENT '引擎类型',
  `should_alert` tinyint(1) NOT NULL DEFAULT '0',
  `confidence` double DEFAULT NULL,
  `latency_ms` bigint(20) DEFAULT NULL,
  `input_json` longtext COMMENT '输入快照',
  `output_json` longtext COMMENT '输出快照',
  `trace_json` longtext COMMENT '决策链路',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_decision_id` (`decision_id`),
  KEY `idx_engine_type` (`engine_type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='决策留痕表';

-- 规则命中统计表
CREATE TABLE IF NOT EXISTS `rule_hit_stat` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `rule_id` varchar(64) NOT NULL,
  `rule_name` varchar(128) DEFAULT NULL,
  `hit_count` bigint(20) DEFAULT '0',
  `miss_count` bigint(20) DEFAULT '0',
  `window_start` datetime DEFAULT CURRENT_TIMESTAMP,
  `window_end` datetime DEFAULT CURRENT_TIMESTAMP,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rule_id` (`rule_id`),
  KEY `idx_window` (`window_start`,`window_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则命中率统计表';

-- 引擎离线评估统计表
CREATE TABLE IF NOT EXISTS `engine_offline_metric` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `engine_type` varchar(32) NOT NULL,
  `sample_size` int(11) DEFAULT '0',
  `false_positive` int(11) DEFAULT '0',
  `false_negative` int(11) DEFAULT '0',
  `precision_score` double DEFAULT NULL,
  `recall_score` double DEFAULT NULL,
  `f1_score` double DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_engine_type` (`engine_type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='引擎离线指标统计表';

-- 决策人工反馈表（离线评估真值来源）
CREATE TABLE IF NOT EXISTS `decision_feedback` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `decision_id` varchar(64) NOT NULL,
  `actual_alert` tinyint(1) NOT NULL DEFAULT '0',
  `reviewer` varchar(64) DEFAULT NULL,
  `remark` varchar(500) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `log_content` text DEFAULT NULL,
  `log_level` varchar(32) DEFAULT NULL,
  `log_template` varchar(255) DEFAULT NULL,
  `model_confidence` double DEFAULT NULL,
  `is_trained` tinyint(1) DEFAULT '0',
  `error_rate_1m` double DEFAULT NULL COMMENT '1分钟错误率',
  `error_1m` double DEFAULT NULL COMMENT '1分钟错误条数',
  `total_1m` double DEFAULT NULL COMMENT '1分钟总量',
  `interval_ms` double DEFAULT NULL COMMENT 'ingest-event 间隔毫秒',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_decision_feedback` (`decision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='决策人工反馈表';

-- 模板管理表
CREATE TABLE IF NOT EXISTS `log_template` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `template_id` varchar(64) NOT NULL COMMENT '模板ID',
  `template_name` varchar(255) DEFAULT NULL,
  `template_content` text NOT NULL,
  `severity` varchar(32) DEFAULT 'INFO',
  `use_count` bigint(20) DEFAULT '0',
  `last_seen_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_id` (`template_id`),
  KEY `idx_use_count` (`use_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日志模板管理表';

-- 智能运维全链路审计（赛题：接收->感知->推理/安全->执行）
CREATE TABLE IF NOT EXISTS `ops_audit_trace` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `trace_id` varchar(64) NOT NULL COMMENT 'traceId',
  `channel` varchar(32) NOT NULL COMMENT 'CHAT | MCP',
  `user_input` text COMMENT '原始指令或构造指令',
  `risk_level` varchar(32) DEFAULT NULL,
  `security_outcome` varchar(64) DEFAULT NULL COMMENT 'PASS | REJECT_*',
  `tool_name` varchar(128) DEFAULT NULL,
  `execution_ok` tinyint(1) DEFAULT '0',
  `result_summary` text COMMENT '结果摘要',
  `steps_json` longtext COMMENT '阶段快照 JSON',
  `duration_ms` bigint(20) DEFAULT NULL,
  `operator_user_id` varchar(64) DEFAULT NULL COMMENT '登录用户ID',
  `policy_version` varchar(64) DEFAULT NULL COMMENT '路径策略版本',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_trace_id` (`trace_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维 Agent 推理链路审计';

-- Agent Workflow Memory（AWM 可复用处置套路）
CREATE TABLE IF NOT EXISTS `ops_workflow_memory` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `workflow_id` varchar(64) NOT NULL COMMENT '唯一 workflow 标识',
  `domain_tag` varchar(32) NOT NULL COMMENT 'disk | cpu | service | security',
  `finding_kinds` varchar(256) DEFAULT NULL COMMENT '逗号分隔 finding kind',
  `title` varchar(255) NOT NULL,
  `description` text,
  `steps_json` longtext NOT NULL COMMENT 'OpsWorkflowStep JSON 数组',
  `source_type` varchar(16) NOT NULL DEFAULT 'seed' COMMENT 'seed | offline | online',
  `source_trace_id` varchar(64) DEFAULT NULL,
  `utility_count` int(11) NOT NULL DEFAULT '0',
  `success_count` int(11) NOT NULL DEFAULT '0',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_id` (`workflow_id`),
  KEY `idx_domain_tag` (`domain_tag`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Workflow Memory';

-- Reflexion 失败教训（仅从 REJECT 轨迹沉淀，不存可执行动作）
CREATE TABLE IF NOT EXISTS `ops_failure_insight` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `insight_key` varchar(160) NOT NULL COMMENT 'securityCode|tool|intent 去重键',
  `security_code` varchar(64) NOT NULL,
  `tool_name` varchar(128) DEFAULT NULL,
  `intent_hint` varchar(512) DEFAULT NULL,
  `reflection` text NOT NULL,
  `source_trace_id` varchar(64) DEFAULT NULL,
  `hit_count` int(11) NOT NULL DEFAULT '1',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_insight_key` (`insight_key`),
  KEY `idx_security_code` (`security_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Reflexion 失败教训';

-- 远程主机资产（SSH 纳管）
CREATE TABLE IF NOT EXISTS `remote_host` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `alias` varchar(128) NOT NULL COMMENT '主机别名',
  `hostname` varchar(255) NOT NULL COMMENT 'IP 或域名',
  `port` int(11) NOT NULL DEFAULT '22' COMMENT 'SSH 端口',
  `username` varchar(64) NOT NULL COMMENT 'SSH 登录用户',
  `auth_type` varchar(16) NOT NULL DEFAULT 'KEY' COMMENT 'KEY | PASSWORD',
  `encrypted_secret` text COMMENT 'AES-GCM 加密的私钥或密码',
  `key_passphrase_encrypted` text COMMENT '私钥口令（加密）',
  `tags` varchar(512) DEFAULT NULL COMMENT '逗号分隔标签',
  `environment` varchar(32) DEFAULT 'production' COMMENT 'production|staging|test|dev',
  `allowed_roles` varchar(128) NOT NULL DEFAULT 'ADMIN,OPS' COMMENT '可执行远程运维的角色',
  `connect_timeout_ms` int(11) NOT NULL DEFAULT '15000',
  `command_timeout_ms` int(11) NOT NULL DEFAULT '60000',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `last_probe_at` datetime DEFAULT NULL,
  `last_probe_ok` tinyint(1) DEFAULT NULL,
  `last_probe_message` varchar(512) DEFAULT NULL,
  `description` text,
  `created_by` varchar(64) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_remote_host_alias` (`alias`),
  KEY `idx_remote_host_enabled` (`enabled`),
  KEY `idx_remote_host_env` (`environment`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SSH 远程主机资产';

-- Runbook approval and execution records
CREATE TABLE IF NOT EXISTS `ops_runbook_approval` (
  `id` bigint(20) NOT NULL,
  `title` varchar(255) NOT NULL,
  `action_name` varchar(255) DEFAULT NULL,
  `command_text` text,
  `tool_name` varchar(128) DEFAULT NULL,
  `parameters_json` longtext,
  `requester` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL,
  `result_code` varchar(128) DEFAULT NULL,
  `execution_implemented` tinyint(1) NOT NULL DEFAULT '0',
  `approver` varchar(64) DEFAULT NULL,
  `reason` text,
  `operator` varchar(64) DEFAULT NULL,
  `trace_id` varchar(64) DEFAULT NULL,
  `mcp_result_json` longtext,
  `mcp_success` tinyint(1) DEFAULT NULL,
  `execution_message` text,
  `write_mismatch` tinyint(1) DEFAULT NULL,
  `write_mode` varchar(64) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `approved_at` datetime DEFAULT NULL,
  `executed_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_runbook_requester_created` (`requester`, `created_at`),
  KEY `idx_runbook_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runbook approval and execution records';
