-- 用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
  `user_id` int(11) NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `password` varchar(128) NOT NULL,
  `role` int(11) DEFAULT '0',
  `wechat_userid` varchar(128) DEFAULT NULL,
  `email` varchar(128) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_username` (`username`)
);

-- 角色表
CREATE TABLE IF NOT EXISTS `sys_role` (
  `role_id` int(11) NOT NULL AUTO_INCREMENT,
  `role_name` varchar(64) NOT NULL,
  `role_desc` varchar(255) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`role_id`),
  UNIQUE KEY `uk_role_name` (`role_name`)
);

-- 权限表
CREATE TABLE IF NOT EXISTS `sys_permission` (
  `permission_id` int(11) NOT NULL AUTO_INCREMENT,
  `permission_name` varchar(64) NOT NULL,
  `permission_code` varchar(64) NOT NULL,
  `parent_id` int(11) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`permission_id`),
  UNIQUE KEY `uk_permission_code` (`permission_code`)
);

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
  `role_id` int(11) NOT NULL,
  `permission_id` int(11) NOT NULL,
  PRIMARY KEY (`role_id`,`permission_id`),
  CONSTRAINT `fk_role_permission_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`role_id`) ON DELETE CASCADE,
  CONSTRAINT `fk_role_permission_permission` FOREIGN KEY (`permission_id`) REFERENCES `sys_permission` (`permission_id`) ON DELETE CASCADE
);

-- 主任务表
CREATE TABLE IF NOT EXISTS `log_analysis_task` (
  `task_id` varchar(64) NOT NULL,
  `user_id` int(11) DEFAULT NULL,
  `file_name` varchar(255) ,
  `status` varchar(32) DEFAULT NULL,
  `progress` int(11) DEFAULT '0',
  `current_step` varchar(255) DEFAULT NULL,
  `error_msg` text,
  `ai_diagnosis` text,
  `total_logs` int(11) DEFAULT '0',
  `anomaly_count` int(11) DEFAULT '0',
  `anomaly_rate` double DEFAULT '0',
  `cost_time` bigint(20) DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`task_id`),
  CONSTRAINT `fk_task_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`user_id`) ON DELETE SET NULL
);

-- 任务详情表（存储具体的异常日志或解析结果）
CREATE TABLE IF NOT EXISTS `log_analysis_detail` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `task_id` varchar(64) NOT NULL,
  `log_time` varchar(64) DEFAULT NULL,
  `severity` varchar(32) DEFAULT NULL,
  `protocol` varchar(64) DEFAULT NULL,
  `pid` varchar(32) DEFAULT NULL,
  `is_anomaly` tinyint(1) DEFAULT '0',
  `anomaly_score` double DEFAULT '0',
  `anomaly_reasons` text,
  `desensitized_log` text,
  `template_id` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_detail_task` FOREIGN KEY (`task_id`) REFERENCES `log_analysis_task` (`task_id`) ON DELETE CASCADE
);

-- 告警规则表
-- 先删除旧表（如果存在）
DROP TABLE IF EXISTS `alarm_rule`;

-- 重新创建alarm_rule表
CREATE TABLE IF NOT EXISTS `alarm_rule` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `rule_type` varchar(20) NOT NULL,
  `rule_expression` text NOT NULL,
  `severity` varchar(20) NOT NULL,
  `push_channels` varchar(50) NOT NULL,
  `enabled` tinyint(1) DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `create_by` varchar(50) DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`id`)
);

-- 插入测试数据
INSERT INTO `alarm_rule` (`name`, `description`, `rule_type`, `rule_expression`, `severity`, `push_channels`, `enabled`) 
VALUES 
('测试告警规则', '测试告警功能，当日志中包含错误关键词时触发', 'KEYWORD', 'error,exception,fail', 'ERROR', 'BOTH', 1),
('警告级别告警规则', '当日志中包含警告关键词时触发', 'KEYWORD', 'warn,warning,alert', 'WARNING', 'EMAIL', 1),
('致命错误告警规则', '当日志中包含致命错误时触发', 'KEYWORD', 'fatal,critical,panic', 'FATAL', 'BOTH', 1);

-- 告警表
CREATE TABLE IF NOT EXISTS `log_alarm` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `alarm_id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `level` varchar(32) NOT NULL,
  `root_cause` text,
  `solution` text,
  `log_content` text,
  `push_status` varchar(64) DEFAULT 'PENDING',
  `lifecycle_status` varchar(32) DEFAULT 'NEW',
  `ack_by` varchar(64) DEFAULT NULL,
  `ack_time` datetime DEFAULT NULL,
  `handled_by` varchar(64) DEFAULT NULL,
  `handled_time` datetime DEFAULT NULL,
  `closed_time` datetime DEFAULT NULL,
  `escalation_level` int(11) DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alarm_id` (`alarm_id`),
  CONSTRAINT `fk_alarm_task` FOREIGN KEY (`task_id`) REFERENCES `log_analysis_task` (`task_id`) ON DELETE CASCADE
);

-- 模型评估记录表
CREATE TABLE IF NOT EXISTS `model_evaluation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `model_version` varchar(64) NOT NULL,
  `sample_size` int(11) NOT NULL,
  `accuracy` double DEFAULT NULL,
  `precision_score` double DEFAULT NULL,
  `recall_score` double DEFAULT NULL,
  `f1_score` double DEFAULT NULL,
  `roc_auc` double DEFAULT NULL,
  `pr_auc` double DEFAULT NULL,
  `confusion_matrix` text,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

-- 决策留痕表
CREATE TABLE IF NOT EXISTS `decision_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `decision_id` varchar(64) NOT NULL,
  `engine_type` varchar(32) NOT NULL,
  `should_alert` tinyint(1) NOT NULL DEFAULT '0',
  `confidence` double DEFAULT NULL,
  `latency_ms` bigint(20) DEFAULT NULL,
  `input_json` longtext,
  `output_json` longtext,
  `trace_json` longtext,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_decision_id` (`decision_id`)
);

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
  PRIMARY KEY (`id`)
);

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
  PRIMARY KEY (`id`)
);

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
  `error_rate_1m` double DEFAULT NULL,
  `error_1m` double DEFAULT NULL,
  `total_1m` double DEFAULT NULL,
  `interval_ms` double DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_decision_feedback` (`decision_id`)
);

-- 模板管理表
CREATE TABLE IF NOT EXISTS `log_template` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `template_id` varchar(64) NOT NULL,
  `template_name` varchar(255) DEFAULT NULL,
  `template_content` text NOT NULL,
  `severity` varchar(32) DEFAULT 'INFO',
  `use_count` bigint(20) DEFAULT '0',
  `last_seen_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_id` (`template_id`)
);

-- 智能运维全链路审计（赛题：接收->感知->推理/安全->执行）
CREATE TABLE IF NOT EXISTS `ops_audit_trace` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `trace_id` varchar(64) NOT NULL,
  `channel` varchar(32) NOT NULL,
  `user_input` text,
  `risk_level` varchar(32) DEFAULT NULL,
  `security_outcome` varchar(64) DEFAULT NULL,
  `tool_name` varchar(128) DEFAULT NULL,
  `execution_ok` tinyint(1) DEFAULT '0',
  `result_summary` text,
  `steps_json` longtext,
  `duration_ms` bigint(20) DEFAULT NULL,
  `operator_user_id` varchar(64) DEFAULT NULL,
  `policy_version` varchar(64) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

-- Agent Workflow Memory（AWM 可复用处置套路）
CREATE TABLE IF NOT EXISTS `ops_workflow_memory` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `workflow_id` varchar(64) NOT NULL,
  `domain_tag` varchar(32) NOT NULL,
  `finding_kinds` varchar(256) DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  `description` text,
  `steps_json` longtext NOT NULL,
  `source_type` varchar(16) NOT NULL DEFAULT 'seed',
  `source_trace_id` varchar(64) DEFAULT NULL,
  `utility_count` int(11) NOT NULL DEFAULT '0',
  `success_count` int(11) NOT NULL DEFAULT '0',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_id` (`workflow_id`)
);

-- Reflexion 失败教训（仅从 REJECT 轨迹沉淀，不存可执行动作）
CREATE TABLE IF NOT EXISTS `ops_failure_insight` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `insight_key` varchar(160) NOT NULL,
  `security_code` varchar(64) NOT NULL,
  `tool_name` varchar(128) DEFAULT NULL,
  `intent_hint` varchar(512) DEFAULT NULL,
  `reflection` text NOT NULL,
  `source_trace_id` varchar(64) DEFAULT NULL,
  `hit_count` int(11) NOT NULL DEFAULT '1',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_insight_key` (`insight_key`)
);

-- 远程主机资产（SSH 纳管）
CREATE TABLE IF NOT EXISTS `remote_host` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `alias` varchar(128) NOT NULL,
  `hostname` varchar(255) NOT NULL,
  `port` int(11) NOT NULL DEFAULT '22',
  `username` varchar(64) NOT NULL,
  `auth_type` varchar(16) NOT NULL DEFAULT 'KEY',
  `encrypted_secret` text,
  `key_passphrase_encrypted` text,
  `tags` varchar(512) DEFAULT NULL,
  `environment` varchar(32) DEFAULT 'production',
  `allowed_roles` varchar(128) NOT NULL DEFAULT 'ADMIN,OPS',
  `connect_timeout_ms` int(11) NOT NULL DEFAULT '15000',
  `command_timeout_ms` int(11) NOT NULL DEFAULT '60000',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `last_probe_at` datetime DEFAULT NULL,
  `last_probe_ok` tinyint(1) DEFAULT NULL,
  `last_probe_message` varchar(512) DEFAULT NULL,
  `description` text,
  `created_by` varchar(64) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_remote_host_alias` (`alias`)
);

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
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);


CREATE TABLE IF NOT EXISTS user_api_key (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id int NOT NULL,
  key_name varchar(128) NOT NULL,
  key_prefix varchar(32) NOT NULL,
  key_hash varchar(255) NOT NULL,
  scope_bundle varchar(255) DEFAULT NULL,
  status varchar(32) NOT NULL,
  last_used_at datetime DEFAULT NULL,
  created_at datetime DEFAULT CURRENT_TIMESTAMP,
  updated_at datetime DEFAULT CURRENT_TIMESTAMP,
  revoked_at datetime DEFAULT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS user_profile_preference (
  user_id int NOT NULL,
  email_enabled tinyint DEFAULT 1,
  sms_enabled tinyint DEFAULT 0,
  task_alerts tinyint DEFAULT 1,
  update_time datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id)
);
