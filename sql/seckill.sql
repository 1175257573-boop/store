-- ============================================================
-- 秒杀模块数据库脚本 seckill.sql
-- 依赖：先执行 ecommerce.sql 建库
-- 执行：mysql -uroot -p123456 --default-character-set=utf8mb4 < seckill.sql
-- ============================================================
USE ecommerce;

-- ------------------------------------------------------------
-- 重建顺序说明
-- 外键是有向的：activity ← stock/order/flow，sku ← stock/order，user ← order。
-- DROP 必须按「先子表后父表」的反序执行，否则删不掉被引用的表。
-- 上一轮建表失败会留下残表和外键，这里统一先清干净再重建。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_seckill_pre_deduct`;  -- 子表
DROP TABLE IF EXISTS `t_seckill_order_item`; -- 子表
DROP TABLE IF EXISTS `t_seckill_order`;       -- 子表
DROP TABLE IF EXISTS `t_seckill_stock`;       -- 子表
DROP TABLE IF EXISTS `t_seckill_activity`;    -- 父表，最后删

CREATE TABLE `t_seckill_activity` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '活动ID',
  `activity_no`  VARCHAR(40) NOT NULL COMMENT '活动业务编号',
  `name`         VARCHAR(100) NOT NULL COMMENT '活动名称',
  `total_stock`  INT         NOT NULL COMMENT '总库存（对账基准，任何时刻不应改变）',
  `bucket_count` INT         NOT NULL DEFAULT 10 COMMENT '库存分桶数量（打散热点 key）',
  `start_time`   DATETIME    NOT NULL COMMENT '开始时间',
  `end_time`     DATETIME    NOT NULL COMMENT '结束时间',
  `status`       TINYINT     NOT NULL DEFAULT 0 COMMENT '状态 0未开始 1进行中 2已结束 3已取消',
  `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_no` (`activity_no`),
  KEY `idx_status_start` (`status`, `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';

-- ------------------------------------------------------------
-- 2. 秒杀库存表
--    库存放在 DB 是为了做最终正确性裁决；Redis 只是前置过滤器
-- ------------------------------------------------------------
CREATE TABLE `t_seckill_stock` (
  `activity_id`  BIGINT NOT NULL COMMENT '活动ID',
  `sku_id`       BIGINT NOT NULL COMMENT '商品ID',
  `total_stock`  INT    NOT NULL COMMENT '该 SKU 总库存（不随售卖变化，对账基准）',
  `available`    INT    NOT NULL COMMENT 'DB 层可用库存，必须 >= 0',
  `locked`       INT    NOT NULL DEFAULT 0 COMMENT '已锁定（下单中未支付）数量',
  `sold`         INT    NOT NULL DEFAULT 0 COMMENT '已售出数量',
  `version`      BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `update_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`activity_id`, `sku_id`),
  CONSTRAINT `fk_seckill_stock_activity` FOREIGN KEY (`activity_id`) REFERENCES `t_seckill_activity` (`id`),
  CONSTRAINT `fk_seckill_stock_sku` FOREIGN KEY (`sku_id`) REFERENCES `t_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀库存表';

CREATE TABLE `t_seckill_order` (
  `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `order_no`     VARCHAR(40)   NOT NULL COMMENT '订单号',
  `activity_id`  BIGINT        NOT NULL COMMENT '活动ID',
  `sku_id`       BIGINT        NOT NULL COMMENT '商品ID',
  `user_id`      BIGINT        NOT NULL COMMENT '用户ID',
  `quantity`     INT           NOT NULL DEFAULT 1 COMMENT '购买数量（秒杀通常为1）',
  `amount`       DECIMAL(10,2) NOT NULL COMMENT '成交金额（下单快照）',
  `status`       TINYINT       NOT NULL DEFAULT 0 COMMENT '状态 0待支付 1已支付 2已取消 3已超时关闭',
  `request_id`   VARCHAR(64)   NOT NULL COMMENT '请求唯一ID（前端生成，用于消费幂等）',
  `pre_deduct_no` VARCHAR(64)  DEFAULT NULL COMMENT '预扣流水号，关联 Redis 预扣记录',
  `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `pay_time`     DATETIME      DEFAULT NULL COMMENT '支付时间',
  `cancel_time`  DATETIME      DEFAULT NULL COMMENT '取消时间',
  `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  -- 幂等最终防线：一个用户在同一活动同一SKU只能有一单
  -- 这是消费端幂等的唯一可靠兜底，Redis 防重失效时由它保证不超卖
  UNIQUE KEY `uk_user_activity_sku` (`user_id`, `activity_id`, `sku_id`),
  -- 补偿任务按此索引扫描「待支付且已超时」的订单
  KEY `idx_status_create` (`status`, `create_time`),
  -- 预扣流水号索引（非唯一）：该字段允许为空（预扣失败时无流水号），
  -- 且消费端幂等已由 uk_user_activity_sku 承担，此处仅供按流水号反查订单
  KEY `idx_pre_deduct_no` (`pre_deduct_no`),
  CONSTRAINT `fk_seckill_order_activity` FOREIGN KEY (`activity_id`) REFERENCES `t_seckill_activity` (`id`),
  CONSTRAINT `fk_seckill_order_sku` FOREIGN KEY (`sku_id`) REFERENCES `t_product` (`id`),
  CONSTRAINT `fk_seckill_order_user` FOREIGN KEY (`user_id`) REFERENCES `t_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单表';

-- ------------------------------------------------------------
-- 4. 秒杀预扣流水表（对账用）
--    记录每一次 Redis 预扣与回补，是对账的唯一依据。
--    没有这张表，Redis 里扣了多少、DB 扣了多少永远对不上账。
-- ------------------------------------------------------------
CREATE TABLE `t_seckill_pre_deduct` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `pre_deduct_no` VARCHAR(64)  NOT NULL COMMENT '预扣流水号（全局唯一）',
  `activity_id`   BIGINT       NOT NULL COMMENT '活动ID',
  `sku_id`        BIGINT       NOT NULL COMMENT '商品ID',
  `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
  `bucket_index`  INT          NOT NULL DEFAULT 0 COMMENT '命中的库存分桶下标',
  `quantity`      INT          NOT NULL COMMENT '预扣数量',
  `direction`     TINYINT      NOT NULL COMMENT '方向 1预扣 -1回补',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0已预扣未落单 1已落单 2已回补',
  `order_id`      BIGINT       DEFAULT NULL COMMENT '关联订单ID',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pre_deduct_no` (`pre_deduct_no`),
  -- 对账任务按此索引扫「已预扣但未落单」的记录
  KEY `idx_flow_status_create` (`status`, `create_time`),
  KEY `idx_activity_status` (`activity_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀预扣流水表';


-- ------------------------------------------------------------
-- 4. 秒杀订单明细表
--    必须独立于 t_order_item：那张表外键指向 t_order（普通订单），
--    秒杀订单在 t_seckill_order，混用会导致外键约束失败。
-- ------------------------------------------------------------
CREATE TABLE `t_seckill_order_item` (
  `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`      BIGINT         NOT NULL COMMENT '秒杀订单ID',
  `order_no`      VARCHAR(40)    NOT NULL COMMENT '订单号（冗余便于查询）',
  `product_id`    BIGINT         NOT NULL COMMENT '商品ID',
  `product_name`  VARCHAR(200)   NOT NULL COMMENT '商品名(下单快照)',
  `product_image` VARCHAR(255)   DEFAULT NULL COMMENT '商品图(快照)',
  `product_price` DECIMAL(10,2)  NOT NULL COMMENT '成交单价(快照)',
  `quantity`      INT            NOT NULL COMMENT '购买数量',
  `subtotal`      DECIMAL(10,2)  NOT NULL COMMENT '小计金额',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `fk_seckill_item_order` FOREIGN KEY (`order_id`)
    REFERENCES `t_seckill_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单明细表';


-- ============================================================
-- 种子数据
-- ============================================================

INSERT INTO `t_seckill_activity`
(`activity_no`, `name`, `total_stock`, `bucket_count`, `start_time`, `end_time`, `status`) VALUES
('SK20261006001', 'iPhone 16 Pro 限量秒杀', 1000, 10,
 '2026-10-01 00:00:00', '2026-12-31 23:59:59', 1),
('SK20261006002', '小米 15 Pro 限量秒杀', 500, 10,
 '2026-10-01 00:00:00', '2026-12-31 23:59:59', 1),
('SK20261006003', '戴森 V12 限量秒杀', 300, 5,
 '2026-10-01 00:00:00', '2026-12-31 23:59:59', 1);

INSERT INTO `t_seckill_stock` (`activity_id`, `sku_id`, `total_stock`, `available`, `locked`, `sold`) VALUES
(1, 1, 1000, 1000, 0, 0),
(2, 3, 500, 500, 0, 0),
(3, 9, 300, 300, 0, 0);
