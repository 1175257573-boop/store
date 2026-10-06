-- ============================================================
-- 商家端数据库脚本 merchant.sql
-- 依赖：先执行 ecommerce.sql
-- 执行：mysql -uroot -p123456 --default-character-set=utf8mb4 < merchant.sql
-- ============================================================
USE ecommerce;

-- ------------------------------------------------------------
-- 1. 给已有表加商家相关字段
--    用 ALTER 而非新建表，保持与用户端/秒杀模块的数据连续性
-- ------------------------------------------------------------

-- 用户表加角色字段：0普通用户 1商家 2平台管理员
-- 角色放在用户表而不是独立角色表：本系统角色固定三类，
-- 独立表只会带来无谓的关联和权限判定复杂度
SET @has_role := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ecommerce' AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'role'
);
SET @sql := IF(@has_role > 0,
  'ALTER TABLE t_user MODIFY COLUMN `role` TINYINT NOT NULL DEFAULT 0 COMMENT ''角色 0用户 1商家 2管理员''',
  'ALTER TABLE t_user ADD COLUMN `role` TINYINT NOT NULL DEFAULT 0 COMMENT ''角色 0用户 1商家 2管理员'' AFTER `status`');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 商品表加商家归属与审核状态
SET @has_mid := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ecommerce' AND TABLE_NAME = 't_product' AND COLUMN_NAME = 'merchant_id'
);
SET @sql := IF(@has_mid > 0,
  'ALTER TABLE t_product MODIFY COLUMN `merchant_id` BIGINT DEFAULT NULL COMMENT ''所属商家ID，NULL为平台自营''',
  'ALTER TABLE t_product ADD COLUMN `merchant_id` BIGINT DEFAULT NULL COMMENT ''所属商家ID，NULL为平台自营'' AFTER `id`');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_audit := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ecommerce' AND TABLE_NAME = 't_product' AND COLUMN_NAME = 'audit_status'
);
SET @sql := IF(@has_audit > 0,
  'ALTER TABLE t_product MODIFY COLUMN `audit_status` TINYINT NOT NULL DEFAULT 0 COMMENT ''审核状态 0待审核 1通过 2拒绝''',
  'ALTER TABLE t_product ADD COLUMN `audit_status` TINYINT NOT NULL DEFAULT 0 COMMENT ''审核状态 0待审核 1通过 2拒绝'' AFTER `status`');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_areason := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ecommerce' AND TABLE_NAME = 't_product' AND COLUMN_NAME = 'audit_reason'
);
SET @sql := IF(@has_areason > 0,
  'ALTER TABLE t_product MODIFY COLUMN `audit_reason` VARCHAR(255) DEFAULT NULL COMMENT ''审核拒绝原因''',
  'ALTER TABLE t_product ADD COLUMN `audit_reason` VARCHAR(255) DEFAULT NULL COMMENT ''审核拒绝原因'' AFTER `audit_status`');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 订单表加商家归属与物流字段
SET @has_omid := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ecommerce' AND TABLE_NAME = 't_order' AND COLUMN_NAME = 'merchant_id'
);
SET @sql := IF(@has_omid > 0,
  'ALTER TABLE t_order MODIFY COLUMN `merchant_id` BIGINT DEFAULT NULL COMMENT ''所属商家ID''',
  'ALTER TABLE t_order ADD COLUMN `merchant_id` BIGINT DEFAULT NULL COMMENT ''所属商家ID'' AFTER `user_id`');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_ship := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ecommerce' AND TABLE_NAME = 't_order' AND COLUMN_NAME = 'ship_company'
);
SET @sql := IF(@has_ship > 0,
  'ALTER TABLE t_order MODIFY COLUMN `ship_company` VARCHAR(50) DEFAULT NULL COMMENT ''快递公司''',
  'ALTER TABLE t_order ADD COLUMN `ship_company` VARCHAR(50) DEFAULT NULL COMMENT ''快递公司'' AFTER `ship_time`');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_shipno := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ecommerce' AND TABLE_NAME = 't_order' AND COLUMN_NAME = 'ship_no'
);
SET @sql := IF(@has_shipno > 0,
  'ALTER TABLE t_order MODIFY COLUMN `ship_no` VARCHAR(50) DEFAULT NULL COMMENT ''快递单号''',
  'ALTER TABLE t_order ADD COLUMN `ship_no` VARCHAR(50) DEFAULT NULL COMMENT ''快递单号'' AFTER `ship_company`');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 订单明细补商家字段，便于商家按自己的商品维度做统计
SET @has_itemmid := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ecommerce' AND TABLE_NAME = 't_order_item' AND COLUMN_NAME = 'merchant_id'
);
SET @sql := IF(@has_itemmid > 0,
  'ALTER TABLE t_order_item MODIFY COLUMN `merchant_id` BIGINT DEFAULT NULL COMMENT ''所属商家ID''',
  'ALTER TABLE t_order_item ADD COLUMN `merchant_id` BIGINT DEFAULT NULL COMMENT ''所属商家ID'' AFTER `product_id`');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. 店铺表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_merchant` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '商家ID',
  `user_id`        BIGINT       NOT NULL COMMENT '关联用户ID（店主账号）',
  `shop_name`      VARCHAR(50)  NOT NULL COMMENT '店铺名称',
  `shop_logo`      VARCHAR(255)          DEFAULT NULL COMMENT '店铺Logo',
  `shop_desc`      VARCHAR(500)          DEFAULT NULL COMMENT '店铺简介',
  `contact_name`   VARCHAR(50)  NOT NULL COMMENT '联系人',
  `contact_phone`  VARCHAR(20)  NOT NULL COMMENT '联系电话',
  `business_type`  TINYINT      NOT NULL DEFAULT 1 COMMENT '经营类目 1个人 2企业',
  `license_no`     VARCHAR(50)           DEFAULT NULL COMMENT '营业执照号（企业必填）',
  `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '店铺状态 1正常 2冻结 3已注销',
  `total_product`  INT          NOT NULL DEFAULT 0 COMMENT '商品总数（冗余统计）',
  `total_order`    BIGINT       NOT NULL DEFAULT 0 COMMENT '累计订单数（冗余统计）',
  `total_sales`    DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '累计销售额（冗余统计）',
  `score`          DECIMAL(3,2) NOT NULL DEFAULT 5.00 COMMENT '店铺评分',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入驻时间',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  -- 一个用户只能开一家店，防止刷店铺刷信用
  UNIQUE KEY `uk_user` (`user_id`),
  UNIQUE KEY `uk_shop_name` (`shop_name`),
  KEY `idx_status` (`status`),
  CONSTRAINT `fk_merchant_user` FOREIGN KEY (`user_id`) REFERENCES `t_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺表';

-- ------------------------------------------------------------
-- 3. 入驻申请表
--    独立申请表：审核通过后才建店铺，历史申请留痕可追溯
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_merchant_apply` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申请ID',
  `user_id`        BIGINT       NOT NULL COMMENT '申请用户ID',
  `shop_name`      VARCHAR(50)  NOT NULL COMMENT '店铺名称',
  `shop_desc`      VARCHAR(500)          DEFAULT NULL COMMENT '店铺简介',
  `contact_name`   VARCHAR(50)  NOT NULL COMMENT '联系人',
  `contact_phone`  VARCHAR(20)  NOT NULL COMMENT '联系电话',
  `business_type`  TINYINT      NOT NULL DEFAULT 1 COMMENT '经营类目 1个人 2企业',
  `license_no`     VARCHAR(50)           DEFAULT NULL COMMENT '营业执照号',
  `id_card`        VARCHAR(30)           DEFAULT NULL COMMENT '身份证号（敏感信息，列表不返回）',
  `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0待审核 1通过 2拒绝 3已撤销',
  `audit_user_id`  BIGINT                DEFAULT NULL COMMENT '审核人ID',
  `audit_remark`   VARCHAR(255)          DEFAULT NULL COMMENT '审核意见',
  `audit_time`     DATETIME              DEFAULT NULL COMMENT '审核时间',
  `merchant_id`    BIGINT                DEFAULT NULL COMMENT '审核通过后生成的店铺ID',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  -- 同一用户只能有一条待审核申请，防止重复提交刷屏
  UNIQUE KEY `uk_user_pending` (`user_id`, `status`),
  KEY `idx_status_time` (`status`, `create_time`),
  CONSTRAINT `fk_apply_user` FOREIGN KEY (`user_id`) REFERENCES `t_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家入驻申请表';

-- ------------------------------------------------------------
-- 4. 商品规格组（如"颜色:红/蓝"、"容量:128G/256G"）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_product_spec` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '规格ID',
  `product_id`  BIGINT      NOT NULL COMMENT '商品ID',
  `name`        VARCHAR(50) NOT NULL COMMENT '规格名（颜色/容量）',
  `sort_order`  INT         NOT NULL DEFAULT 0 COMMENT '排序',
  PRIMARY KEY (`id`),
  KEY `idx_product` (`product_id`),
  CONSTRAINT `fk_spec_product` FOREIGN KEY (`product_id`) REFERENCES `t_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品规格组';

-- ------------------------------------------------------------
-- 5. 商品规格值（如"红"、"256G"）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_product_spec_value` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '规格值ID',
  `spec_id`     BIGINT      NOT NULL COMMENT '规格ID',
  `value`       VARCHAR(50) NOT NULL COMMENT '规格值',
  `sort_order`  INT         NOT NULL DEFAULT 0 COMMENT '排序',
  PRIMARY KEY (`id`),
  KEY `idx_spec` (`spec_id`),
  CONSTRAINT `fk_spec_value_spec` FOREIGN KEY (`spec_id`) REFERENCES `t_product_spec` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品规格值';

-- ------------------------------------------------------------
-- 6. 商品 SKU（颜色×容量的组合，各自有独立价格与库存）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_product_sku` (
  `id`          BIGINT         NOT NULL AUTO_INCREMENT COMMENT 'SKU ID',
  `product_id`  BIGINT         NOT NULL COMMENT '商品ID',
  `merchant_id` BIGINT         NOT NULL COMMENT '所属商家ID（冗余，便于按商家查）',
  `spec_text`   VARCHAR(200)   NOT NULL COMMENT '规格组合文本，如 "红色 / 256GB"',
  `price`       DECIMAL(10,2)  NOT NULL COMMENT 'SKU售价',
  `stock`       INT            NOT NULL DEFAULT 0 COMMENT 'SKU库存',
  `sales`       INT            NOT NULL DEFAULT 0 COMMENT 'SKU销量',
  `sku_code`    VARCHAR(64)             DEFAULT NULL COMMENT '商家编码',
  `status`      TINYINT        NOT NULL DEFAULT 1 COMMENT '状态 1启用 0禁用',
  `sort_order`  INT            NOT NULL DEFAULT 0 COMMENT '排序',
  `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_spec` (`product_id`, `spec_text`),
  KEY `idx_merchant_status` (`merchant_id`, `status`),
  KEY `idx_product` (`product_id`),
  CONSTRAINT `fk_sku_product` FOREIGN KEY (`product_id`) REFERENCES `t_product` (`id`),
  CONSTRAINT `fk_sku_merchant` FOREIGN KEY (`merchant_id`) REFERENCES `t_merchant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SKU表';

-- ------------------------------------------------------------
-- 7. 售后表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_after_sale` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '售后单ID',
  `sale_no`       VARCHAR(40)   NOT NULL COMMENT '售后单号',
  `order_id`      BIGINT        NOT NULL COMMENT '订单ID',
  `order_no`      VARCHAR(40)   NOT NULL COMMENT '订单号（冗余）',
  `user_id`       BIGINT        NOT NULL COMMENT '申请用户ID',
  `merchant_id`   BIGINT        NOT NULL COMMENT '商家ID',
  `product_id`    BIGINT        NOT NULL COMMENT '商品ID',
  `product_name`  VARCHAR(200)  NOT NULL COMMENT '商品名（快照）',
  `quantity`      INT           NOT NULL DEFAULT 1 COMMENT '售后数量',
  `amount`        DECIMAL(10,2) NOT NULL COMMENT '退款金额',
  `type`          TINYINT       NOT NULL DEFAULT 1 COMMENT '类型 1仅退款 2退货退款',
  `reason`        VARCHAR(100)  NOT NULL COMMENT '售后原因',
  `remark`        VARCHAR(500)          DEFAULT NULL COMMENT '补充说明',
  `images`        VARCHAR(1000)         DEFAULT NULL COMMENT '凭证图片，逗号分隔',
  `status`        TINYINT       NOT NULL DEFAULT 0 COMMENT '状态 0待处理 1已同意 2已拒绝 3已完成 4已撤销',
  `audit_remark`  VARCHAR(255)          DEFAULT NULL COMMENT '商家处理意见',
  `audit_time`    DATETIME               DEFAULT NULL COMMENT '处理时间',
  `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sale_no` (`sale_no`),
  KEY `idx_merchant_status` (`merchant_id`, `status`),
  KEY `idx_user` (`user_id`),
  CONSTRAINT `fk_sale_order` FOREIGN KEY (`order_id`) REFERENCES `t_order` (`id`),
  CONSTRAINT `fk_sale_merchant` FOREIGN KEY (`merchant_id`) REFERENCES `t_merchant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后表';


-- ============================================================
-- 种子数据
-- ============================================================

-- 补充分类：家居家纺、办公文具（商家商品需要归属分类）
INSERT INTO t_category (id, name, icon, sort_order, status) VALUES
(7, '家居家纺', '🛏️', 65, 1),
(8, '办公文具', '✏️', 45, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 管理员账号 admin 提权为平台管理员（密码 123456）
UPDATE `t_user` SET `role` = 2 WHERE `username` = 'admin';

-- 平台自营商品：merchant_id 置 NULL，审核状态直接通过
UPDATE `t_product` SET `merchant_id` = NULL, `audit_status` = 1 WHERE `merchant_id` IS NULL;

-- 演示商家：password 复用 demo 用户的 BCrypt 哈希
SET @demo_pwd := (SELECT `password` FROM `t_user` WHERE `username` = 'demo');
INSERT INTO `t_user` (`username`, `password`, `nickname`, `phone`, `email`, `role`) VALUES
('shop_a', @demo_pwd, '优品旗舰店', '13711110001', 'shop_a@shop.com', 1),
('shop_b', @demo_pwd, '数码专营店', '13711110002', 'shop_b@shop.com', 1)
ON DUPLICATE KEY UPDATE `role` = 1;

INSERT INTO `t_merchant`
(`user_id`, `shop_name`, `shop_desc`, `contact_name`, `contact_phone`, `business_type`, `status`, `score`) VALUES
((SELECT id FROM t_user WHERE username = 'shop_a'), '优品旗舰店', '专注品质生活好物，正品保障', '张店主', '13711110001', 2, 1, 4.90),
((SELECT id FROM t_user WHERE username = 'shop_b'), '数码专营店', '数码产品一站式服务商', '李店主', '13711110002', 1, 1, 4.80)
ON DUPLICATE KEY UPDATE `status` = 1;

-- 给两个商家各挂一批商品，走完整的「待审核 -> 通过」流程
INSERT INTO `t_product`
(`merchant_id`, `category_id`, `name`, `subtitle`, `description`, `main_image`,
 `price`, `origin_price`, `stock`, `sales`, `view_count`, `status`, `audit_status`)
VALUES
((SELECT id FROM t_merchant WHERE shop_name='优品旗舰店'), 7, '北欧风陶瓷餐具套装 16 件', '釉面彩绘 | 微波炉可用 | 洗碗机适用',
 '精选高白泥陶瓷，经高温釉烧制，釉面彩绘工艺。包含碗、盘、筷、勺共 16 件，适合家庭日常使用。微波炉、洗碗机均可使用。',
 'https://picsum.photos/seed/ceramic/600/600', 299.00, 399.00, 800, 0, 0, 1, 1),
((SELECT id FROM t_merchant WHERE shop_name='优品旗舰店'), 7, '全棉四件套 1.8m 床', 'A类新疆棉 | 60支贡缎 | 不起球',
 'A 类新疆长绒棉，60 支贡缎工艺，密度 200 支以上。亲肤透气，多次洗涤不易起球。含被套、床单及枕套两只。',
 'https://picsum.photos/seed/bedding/600/600', 459.00, 599.00, 500, 0, 0, 1, 1),
((SELECT id FROM t_merchant WHERE shop_name='数码专营店'), 8, '机械键盘 87键 客制化', '热插拔 | Gasket结构 | 三模连接',
 'Gasket 结构设计，五层填充消音。支持热插拔换轴。三模连接支持有线、2.4G 与蓝牙，可同时连接三台设备。',
 'https://picsum.photos/seed/keyboard/600/600', 399.00, 499.00, 300, 0, 0, 1, 1),
((SELECT id FROM t_merchant WHERE shop_name='数码专营店'), 8, '人体工学椅 网布款', '四向扶手 | 动态腰托 | 静音轮',
 '动态腰托贴合脊柱曲线，四向可调扶手适配不同坐姿。网布透气，久坐不闷。静音 PU 万向轮不伤地板。',
 'https://picsum.photos/seed/chair/600/600', 899.00, 1199.00, 200, 0, 0, 1, 1),
-- 待审核商品：让前端能演示「审核中」状态
((SELECT id FROM t_merchant WHERE shop_name='数码专营店'), 8, '便携投影仪 1080P（待审核）', '自动对焦 | 1080P | 内置电池',
 '原生 1080P 分辨率，自动对焦与梯形校正，内置电池支持 3 小时播放。',
 'https://picsum.photos/seed/projector/600/600', 1299.00, 1599.00, 150, 0, 0, 0, 0)
ON DUPLICATE KEY UPDATE `audit_status` = 1;
