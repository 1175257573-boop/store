-- ============================================================
-- 买家 ↔ 店铺 聊天（IM）
--
-- 设计要点（都是踩过的坑）
-- --------------------------------
-- 1. 会话按「买家 × 店铺」唯一，不按商品
--    买家问「这能退吗」后应该能继续问「那台呢」——
--    若按商品建会话，换商品就断了。
--
-- 2. 唯一键 uk_buyer_merchant 保证一对一会话不重复创建
--    再次咨询时复用原会话，历史消息才能延续。
--
-- 3. 双侧未读数分开（buyer_unread / merchant_unread）
--    未读的方向不同，不能用同一个字段。
--
-- 4. last_message + last_time 冗余在会话表
--    会话列表要显示摘要并按时间排序，
--    冗余后不必每行都 join 消息表。
--
-- 5. 商品归属 merchant_id 是 IM 的归属依据
--    所以商品迁移时无需动 IM 数据 —— 会话跟着店铺走。
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_im_session` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  `buyer_id`       BIGINT       NOT NULL COMMENT '买家用户ID',
  `merchant_id`    BIGINT       NOT NULL COMMENT '店铺ID',
  `product_id`     BIGINT                DEFAULT NULL COMMENT '关联商品（从商品页发起时带入）',
  `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 2买家删除 3商家删除',
  `buyer_unread`   INT          NOT NULL DEFAULT 0 COMMENT '买家未读数',
  `merchant_unread` INT         NOT NULL DEFAULT 0 COMMENT '商家未读数',
  `last_message`   VARCHAR(500)          DEFAULT NULL COMMENT '最后一条消息摘要',
  `last_time`      DATETIME              DEFAULT NULL COMMENT '最后消息时间（列表排序）',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 一对一会话唯一：买家与同一店铺只有一条会话
  UNIQUE KEY `uk_buyer_merchant` (`buyer_id`, `merchant_id`),
  KEY `idx_buyer_time` (`buyer_id`, `last_time` DESC),
  KEY `idx_merchant_unread` (`merchant_id`, `merchant_unread`),
  KEY `idx_merchant_time` (`merchant_id`, `last_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='买家-店铺会话';

CREATE TABLE IF NOT EXISTS `t_im_message` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  `session_id`  BIGINT        NOT NULL COMMENT '会话ID',
  `from_id`     BIGINT        NOT NULL COMMENT '发送者ID',
  `from_role`   TINYINT       NOT NULL COMMENT '0买家 1商家（冗余，省一次查表）',
  `to_id`       BIGINT        NOT NULL COMMENT '接收者ID',
  `content`     VARCHAR(1000) NOT NULL COMMENT '消息内容',
  `read_flag`   TINYINT       NOT NULL DEFAULT 0 COMMENT '1已读',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 支撑「按会话翻消息」与增量拉取（轮询用 afterId）
  KEY `idx_session_id_time` (`session_id`, `id`),
  -- 支撑「我的未读」批量查询与标记已读
  KEY `idx_to_read` (`to_id`, `read_flag`),
  KEY `idx_session_unread` (`session_id`, `to_id`, `read_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息';

-- ============================================================
-- 会话快照：用于买家端展示「我的店铺会话」时避免 join 商品表
-- 不加也能跑（join 商品表），加了性能更好。
-- 先不加，保持最小表结构；需要时再加。
-- ============================================================