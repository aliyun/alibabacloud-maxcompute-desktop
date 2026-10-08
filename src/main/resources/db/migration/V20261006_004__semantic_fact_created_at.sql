-- 记忆 created_at 补缺（评审 M0 #9）:此前表只有 updated_at,前端 MemoryDetailTab 读
-- created_at 恒 undefined 显示「未记录」。加列 + 存量回填 updated_at(创建时间不可考,
-- 以最近更新时间为下限近似,好于永久空值)。
ALTER TABLE semantic_fact ADD COLUMN created_at INTEGER;
UPDATE semantic_fact SET created_at = updated_at WHERE created_at IS NULL;
