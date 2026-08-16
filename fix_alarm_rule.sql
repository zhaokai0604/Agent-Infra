-- 修复alarm_rule表结构脚本

-- 1. 检查并删除旧表（如果存在且结构不正确）
DROP TABLE IF EXISTS `alarm_rule`;

-- 2. 重新创建alarm_rule表
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

-- 3. 插入测试数据
INSERT INTO `alarm_rule` (`name`, `description`, `rule_type`, `rule_expression`, `severity`, `push_channels`, `enabled`) 
VALUES 
('测试告警规则', '测试告警功能，当日志中包含错误关键词时触发', 'KEYWORD', 'error,exception,fail', 'ERROR', 'BOTH', 1),
('警告级别告警规则', '当日志中包含警告关键词时触发', 'KEYWORD', 'warn,warning,alert', 'WARNING', 'EMAIL', 1),
('致命错误告警规则', '当日志中包含致命错误时触发', 'KEYWORD', 'fatal,critical,panic', 'FATAL', 'BOTH', 1);

-- 4. 验证表结构
DESCRIBE `alarm_rule`;

-- 5. 验证数据
SELECT * FROM `alarm_rule`;