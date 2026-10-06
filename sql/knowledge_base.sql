-- ============================================================
-- 智能客服知识库
--
-- 设计目标：同时支持 BM25 字面检索与向量语义检索。
--
-- 为什么不用单表存大段详情：
--   1. 详情动辄上千字，整段做向量匹配会被平均掉，召回不精确；
--   2. 客服问「续航多久」时，期望命中的是续航那一段，不是整段详情；
--   3. 分块后每块可独立更新（改价格不必重算全段向量）。
--
-- 因此采用「块(chunk)」为最小单位：一块 = 一个语义自足的事实单元。
-- 每块都有 title（便于 BM25 加权与人工核对）与 content（用于向量化）。
-- ============================================================

-- ------------------------------------------------------------
-- 表 1：商品知识块
-- chunk_type 决定该块回答什么问题，客服按问题类型路由到对应类型
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_kb_product_chunk` (
  `id`          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '块 ID',
  `product_id`  BIGINT         NOT NULL COMMENT '商品 ID',
  `category_id` BIGINT         NOT NULL COMMENT '分类 ID（冗余，便于按分类召回）',
  `chunk_type`  VARCHAR(24)    NOT NULL COMMENT '块类型 spec/sfeature/usecase/warranty/price/compare/faq',
  `title`       VARCHAR(120)   NOT NULL COMMENT '块标题，如「核心参数」「续航能力」',
  `content`     TEXT           NOT NULL COMMENT '块正文，一段完整语义，供 BM25 与向量化共用',
  `keywords`    VARCHAR(255)            DEFAULT NULL COMMENT '关键词，逗号分隔，BM25 加权与召回兜底',
  `weight`      INT            NOT NULL DEFAULT 1 COMMENT '权重 1-5，越大越优先召回',
  `status`      TINYINT        NOT NULL DEFAULT 1 COMMENT '状态 1启用 0禁用',
  `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_product` (`product_id`),
  KEY `idx_type` (`chunk_type`),
  KEY `idx_category` (`category_id`),
  KEY `idx_weight` (`weight` DESC),
  -- ngram 分词器：MySQL 5.7 内置，中文按 2-gram 切分，无需外部插件
  -- title/keywords 单独建索引便于 BM25 加权：
  -- 标题命中权重高于正文，keywords 命中说明主题词直接出现
  FULLTEXT KEY `ft_content` (`title`, `content`, `keywords`)
    WITH PARSER ngram
) COMMENT='商品知识块：智能客服的商品侧知识，按语义分块';

-- ------------------------------------------------------------
-- 表 2：问答对
-- 客服的显式知识：问什么、答什么、属于哪类问题
-- 命中率最高的路径是精确命中 question，所以这里建唯一索引
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_kb_faq` (
  `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '问答 ID',
  `product_id`    BIGINT                  DEFAULT NULL COMMENT '关联商品 ID，NULL 表示通用问题',
  `category_id`   BIGINT                  DEFAULT NULL COMMENT '关联分类，NULL 表示通用问题',
  `question`      VARCHAR(255)   NOT NULL COMMENT '标准问法',
  `answer`        TEXT           NOT NULL COMMENT '标准答法',
  `question_kw`   VARCHAR(255)            DEFAULT NULL COMMENT '问法关键词，逗号分隔，模糊匹配用',
  `intent`        VARCHAR(32)    NOT NULL DEFAULT 'unknown' COMMENT '意图：price/stock/spec/compat/warranty/logistics/after_sale/compare',
  `priority`      INT            NOT NULL DEFAULT 3 COMMENT '优先级 1最高',
  `hit_count`     INT            NOT NULL DEFAULT 0 COMMENT '命中次数，用于排序热门问题',
  `status`        TINYINT        NOT NULL DEFAULT 1,
  `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_product` (`product_id`),
  KEY `idx_intent` (`intent`),
  KEY `idx_priority` (`priority`),
  KEY `idx_question` (`question`),
  FULLTEXT KEY `ft_question` (`question`, `question_kw`, `answer`)
    WITH PARSER ngram
) COMMENT='客服问答对：显式问答知识';

-- ------------------------------------------------------------
-- 表 3：店铺级通用知识
-- 物流、售后、优惠、支付等与具体商品无关的问题
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_kb_shop_knowledge` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT               DEFAULT NULL COMMENT 'NULL = 平台级规则',
  `topic`      VARCHAR(32)  NOT NULL COMMENT '主题：logistics/after_sale/payment/promotion/member',
  `question`   VARCHAR(255) NOT NULL,
  `answer`     TEXT         NOT NULL,
  `keywords`   VARCHAR(255)         DEFAULT NULL,
  `weight`     INT          NOT NULL DEFAULT 1,
  `status`     TINYINT      NOT NULL DEFAULT 1,
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_topic` (`topic`),
  KEY `idx_merchant` (`merchant_id`),
  FULLTEXT KEY `ft_kb` (`question`, `answer`, `keywords`)
    WITH PARSER ngram
) COMMENT='店铺与平台通用知识：物流售后等非商品维度问答';

-- ------------------------------------------------------------
-- 表 4：结构化商品属性
-- 客服需要精确匹配的场景：「有黑色吗」「库存多少」「多少钱」
-- 这类问题不能靠语义相似度，必须有结构化字段做等值/区间查询
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_product_attr` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `product_id`  BIGINT        NOT NULL,
  `attr_key`    VARCHAR(48)   NOT NULL COMMENT '属性键，如 brand/model/battery/screen_size',
  `attr_name`   VARCHAR(48)   NOT NULL COMMENT '属性显示名，如「电池容量」',
  `attr_value`  VARCHAR(255)  NOT NULL COMMENT '属性值，数值型请带单位',
  `value_num`   DECIMAL(14,4)         DEFAULT NULL COMMENT '数值化后的值，用于区间查询与排序',
  `unit`        VARCHAR(24)            DEFAULT NULL COMMENT '单位，如 mAh/英寸/GB',
  `group_name`  VARCHAR(32)   NOT NULL DEFAULT '基本参数' COMMENT '属性分组',
  `sort_order`  INT           NOT NULL DEFAULT 0,
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_attr` (`product_id`, `attr_key`),
  KEY `idx_key` (`attr_key`),
  KEY `idx_value_num` (`attr_key`, `value_num`),
  KEY `idx_group` (`product_id`, `group_name`)
) COMMENT='商品结构化属性：供客服做精确匹配与区间筛选';
