-- ============================================================
-- 秒杀活动管理增强（管理员/商家发布活动）
-- 依赖：先执行 seckill.sql
-- 执行：mysql -uroot -p123456 --default-character-set=utf8mb4 < seckill_admin.sql
-- ============================================================
USE ecommerce;

-- ------------------------------------------------------------
-- t_seckill_activity 扩展：
--   归属商家（NULL = 平台自建的活动）
--   每人限购数量（1 = 每人只能抢 1 件）
--   活动说明、封面
-- ------------------------------------------------------------
SET @has := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='ecommerce' AND TABLE_NAME='t_seckill_activity'
    AND COLUMN_NAME='merchant_id'
);
SET @sql := IF(@has > 0,
  'ALTER TABLE t_seckill_activity MODIFY COLUMN `merchant_id` BIGINT DEFAULT NULL COMMENT ''归属商家ID，NULL为平台自建''',
  'ALTER TABLE t_seckill_activity ADD COLUMN `merchant_id` BIGINT DEFAULT NULL COMMENT ''归属商家ID，NULL为平台自建'' AFTER `activity_no`');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='ecommerce' AND TABLE_NAME='t_seckill_activity'
    AND COLUMN_NAME='limit_per_user'
);
SET @sql := IF(@has > 0,
  'ALTER TABLE t_seckill_activity MODIFY COLUMN `limit_per_user` INT NOT NULL DEFAULT 1 COMMENT ''每人限购数量''',
  'ALTER TABLE t_seckill_activity ADD COLUMN `limit_per_user` INT NOT NULL DEFAULT 1 COMMENT ''每人限购数量'' AFTER `bucket_count`');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='ecommerce' AND TABLE_NAME='t_seckill_activity'
    AND COLUMN_NAME='cover_image'
);
SET @sql := IF(@has > 0,
  'ALTER TABLE t_seckill_activity MODIFY COLUMN `cover_image` VARCHAR(255) DEFAULT NULL COMMENT ''活动封面''',
  'ALTER TABLE t_seckill_activity ADD COLUMN `cover_image` VARCHAR(255) DEFAULT NULL COMMENT ''活动封面'' AFTER `name`');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='ecommerce' AND TABLE_NAME='t_seckill_activity'
    AND COLUMN_NAME='description'
);
SET @sql := IF(@has > 0,
  'ALTER TABLE t_seckill_activity MODIFY COLUMN `description` VARCHAR(500) DEFAULT NULL COMMENT ''活动说明''',
  'ALTER TABLE t_seckill_activity ADD COLUMN `description` VARCHAR(500) DEFAULT NULL COMMENT ''活动说明'' AFTER `name`');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- t_seckill_stock 扩展：加限购校验需要的字段
--   已有的 available / locked / sold / total_stock 保持不变
-- ------------------------------------------------------------
-- 注意：不限购时 limit_per_user=1，即每人只能抢 1 件
-- 这已经覆盖了秒杀最常见的限购场景，无需额外字段

-- ------------------------------------------------------------
-- 索引补充：按商家 + 状态查活动
-- ------------------------------------------------------------
SET @has := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA='ecommerce' AND TABLE_NAME='t_seckill_activity'
    AND INDEX_NAME='idx_merchant_status'
);
SET @sql := IF(@has > 0, 'SELECT 1',
  'ALTER TABLE t_seckill_activity ADD INDEX `idx_merchant_status` (`merchant_id`, `status`)');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- 演示数据：商家发布的一场活动
-- ------------------------------------------------------------
INSERT INTO `t_seckill_activity`
(`activity_no`, `merchant_id`, `name`, `cover_image`, `description`,
 `total_stock`, `bucket_count`, `limit_per_user`, `start_time`, `end_time`, `status`)
VALUES
('SK20260606001',
 (SELECT id FROM t_merchant WHERE shop_name = '数码专营店' LIMIT 1),
 '机械键盘限时秒杀',
 'https://picsum.photos/seed/seckill-cover/800/400',
 '全场机械键盘特惠，限量抢购，先到先得',
 300, 10, 1,
 '2026-10-01 00:00:00', '2026-12-31 23:59:59', 1)
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`);

-- 库存记录：活动1 已存在（iPhone），这里补一条机械键盘的
INSERT INTO `t_seckill_stock` (`activity_id`, `sku_id`, `total_stock`, `available`, `locked`, `sold`)
SELECT a.id, 16, 300, 300, 0, 0
FROM `t_seckill_activity` a
WHERE a.activity_no = 'SK20260606001'
  AND NOT EXISTS (
    SELECT 1 FROM `t_seckill_stock` s WHERE s.activity_id = a.id AND s.sku_id = 16
  );

-- 确认改动
SELECT '活动表字段' AS 项目, GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) AS 内容
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='ecommerce' AND TABLE_NAME='t_seckill_activity'
UNION ALL
SELECT '活动记录数', CAST(COUNT(*) AS CHAR) FROM `t_seckill_activity`;
