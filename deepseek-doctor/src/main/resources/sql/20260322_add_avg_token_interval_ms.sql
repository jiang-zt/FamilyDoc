ALTER TABLE chat_metric
ADD COLUMN avg_token_interval_ms DECIMAL(10,2) NULL COMMENT '相邻输出token（或chunk）平均到达间隔（毫秒）'
AFTER total_ms;
