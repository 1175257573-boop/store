-- ============================================================
-- 电商平台数据库初始化脚本 ecommerce.sql
-- 适用：MySQL 5.7+ / 8.0   字符集：utf8mb4
-- 执行：mysql -uroot -p123456 < ecommerce.sql
-- ============================================================

DROP DATABASE IF EXISTS ecommerce;
CREATE DATABASE ecommerce DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE ecommerce;

-- ------------------------------------------------------------
-- 1. 用户表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username`      VARCHAR(50)  NOT NULL COMMENT '登录用户名',
  `password`      VARCHAR(100) NOT NULL COMMENT '密码(BCrypt 哈希)',
  `nickname`      VARCHAR(50)  NOT NULL COMMENT '昵称',
  `phone`         VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
  `email`         VARCHAR(100)          DEFAULT NULL COMMENT '邮箱',
  `avatar`        VARCHAR(255)          DEFAULT NULL COMMENT '头像地址',
  `gender`        TINYINT      NOT NULL DEFAULT 0 COMMENT '性别 0未知 1男 2女',
  `birthday`      DATE                  DEFAULT NULL COMMENT '生日',
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1正常 0禁用',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ------------------------------------------------------------
-- 2. 商品分类表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_category`;
CREATE TABLE `t_category` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `name`        VARCHAR(50) NOT NULL COMMENT '分类名称',
  `icon`        VARCHAR(255)         DEFAULT NULL COMMENT '分类图标',
  `sort_order`  INT         NOT NULL DEFAULT 0 COMMENT '排序值，越大越靠前',
  `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- ------------------------------------------------------------
-- 3. 商品表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_product`;
CREATE TABLE `t_product` (
  `id`          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '商品ID',
  `category_id` BIGINT         NOT NULL COMMENT '所属分类ID',
  `name`        VARCHAR(200)   NOT NULL COMMENT '商品名称',
  `subtitle`    VARCHAR(255)            DEFAULT NULL COMMENT '副标题/卖点',
  `description` TEXT COMMENT '商品详情描述',
  `main_image`  VARCHAR(255)            DEFAULT NULL COMMENT '主图URL',
  `price`       DECIMAL(10, 2) NOT NULL COMMENT '销售价(元)',
  `origin_price` DECIMAL(10, 2)        DEFAULT NULL COMMENT '划线价(元)',
  `stock`       INT            NOT NULL DEFAULT 0 COMMENT '库存数量',
  `sales`       INT            NOT NULL DEFAULT 0 COMMENT '累计销量',
  `view_count`  INT            NOT NULL DEFAULT 0 COMMENT '浏览量',
  `status`      TINYINT        NOT NULL DEFAULT 1 COMMENT '状态 1上架 0下架',
  `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上架时间',
  `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category_id`),
  KEY `idx_status_price` (`status`, `price`),
  KEY `idx_sales` (`sales`),
  KEY `idx_name` (`name`),
  -- 注：MySQL 5.7 内置分词器仅有 ngram（需插件），为保证跨版本可移植，
  -- 关键字检索统一走 LIKE '%%keyword%%' 走 idx_name 索引前缀，此处不建 FULLTEXT
  CONSTRAINT `fk_product_category` FOREIGN KEY (`category_id`) REFERENCES `t_category` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- ------------------------------------------------------------
-- 4. 购物车表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_cart_item`;
CREATE TABLE `t_cart_item` (
  `id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT NOT NULL COMMENT '用户ID',
  `product_id`  BIGINT NOT NULL COMMENT '商品ID',
  `quantity`    INT    NOT NULL DEFAULT 1 COMMENT '购买数量',
  `checked`     TINYINT NOT NULL DEFAULT 1 COMMENT '是否勾选 1是 0否',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_product` (`user_id`, `product_id`),
  KEY `idx_user` (`user_id`),
  CONSTRAINT `fk_cart_user` FOREIGN KEY (`user_id`) REFERENCES `t_user` (`id`),
  CONSTRAINT `fk_cart_product` FOREIGN KEY (`product_id`) REFERENCES `t_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车表';

-- ------------------------------------------------------------
-- 5. 收货地址表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_address`;
CREATE TABLE `t_address` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '地址ID',
  `user_id`    BIGINT      NOT NULL COMMENT '用户ID',
  `receiver`   VARCHAR(50) NOT NULL COMMENT '收货人',
  `phone`      VARCHAR(20) NOT NULL COMMENT '联系电话',
  `province`   VARCHAR(50) NOT NULL COMMENT '省份',
  `city`       VARCHAR(50) NOT NULL COMMENT '城市',
  `district`   VARCHAR(50) NOT NULL COMMENT '区县',
  `detail`     VARCHAR(255) NOT NULL COMMENT '详细地址',
  `is_default` TINYINT     NOT NULL DEFAULT 0 COMMENT '是否默认地址 1是 0否',
  `create_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  CONSTRAINT `fk_address_user` FOREIGN KEY (`user_id`) REFERENCES `t_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货地址表';

-- ------------------------------------------------------------
-- 6. 订单主表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_order`;
CREATE TABLE `t_order` (
  `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `order_no`      VARCHAR(40)    NOT NULL COMMENT '订单号(业务唯一)',
  `user_id`       BIGINT         NOT NULL COMMENT '下单用户ID',
  `total_amount`  DECIMAL(10, 2) NOT NULL COMMENT '订单总金额',
  `pay_amount`    DECIMAL(10, 2) NOT NULL COMMENT '实付金额',
  `status`        TINYINT        NOT NULL DEFAULT 0 COMMENT '订单状态 0待付款 1已付款 2已发货 3已完成 4已取消',
  `receiver`      VARCHAR(50)    NOT NULL COMMENT '收货人(下单快照)',
  `phone`         VARCHAR(20)    NOT NULL COMMENT '联系电话(快照)',
  `address`       VARCHAR(500)   NOT NULL COMMENT '完整地址(快照)',
  `remark`        VARCHAR(500)            DEFAULT NULL COMMENT '用户备注',
  `pay_time`      DATETIME                DEFAULT NULL COMMENT '支付时间',
  `ship_time`     DATETIME                DEFAULT NULL COMMENT '发货时间',
  `finish_time`   DATETIME                DEFAULT NULL COMMENT '完成时间',
  `cancel_time`   DATETIME                DEFAULT NULL COMMENT '取消时间',
  `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `update_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_create_time` (`create_time`),
  CONSTRAINT `fk_order_user` FOREIGN KEY (`user_id`) REFERENCES `t_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';

-- ------------------------------------------------------------
-- 7. 订单明细表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_order_item`;
CREATE TABLE `t_order_item` (
  `id`          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`    BIGINT         NOT NULL COMMENT '订单ID',
  `order_no`    VARCHAR(40)    NOT NULL COMMENT '订单号(冗余便于查询)',
  `product_id`  BIGINT         NOT NULL COMMENT '商品ID',
  `product_name` VARCHAR(200)  NOT NULL COMMENT '商品名(下单快照)',
  `product_image` VARCHAR(255)          DEFAULT NULL COMMENT '商品图(快照)',
  `product_price` DECIMAL(10,2) NOT NULL COMMENT '成交单价(快照)',
  `quantity`    INT            NOT NULL COMMENT '购买数量',
  `subtotal`    DECIMAL(10, 2) NOT NULL COMMENT '小计金额',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `fk_item_order` FOREIGN KEY (`order_id`) REFERENCES `t_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';


-- ============================================================
-- 种子数据
-- ============================================================

-- 测试用户，密码均为 123456（BCrypt 哈希）
INSERT INTO `t_user` (`username`, `password`, `nickname`, `phone`, `email`, `gender`) VALUES
('admin',  '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '系统管理员', '13800000000', 'admin@shop.com', 1),
('demo',   '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '演示用户', '13900000001', 'demo@shop.com', 1);

-- 商品分类
INSERT INTO `t_category` (`id`, `name`, `icon`, `sort_order`, `status`) VALUES
(1, '手机通讯', '📱', 100, 1),
(2, '电脑办公', '💻',  90, 1),
(3, '家用电器', '🔌',  80, 1),
(4, '服饰鞋包', '👕',  70, 1),
(5, '食品生鲜', '🍎',  60, 1),
(6, '图书文娱', '📚',  50, 1);

-- 商品
INSERT INTO `t_product`
(`category_id`, `name`, `subtitle`, `description`, `main_image`, `price`, `origin_price`, `stock`, `sales`, `view_count`, `status`) VALUES
(1, 'Apple iPhone 16 Pro 256GB 原色钛金属', 'A17 Pro 芯片 | 6.3英寸超视网膜XDR | 钛金属中框',
 'Apple iPhone 16 Pro 搭载 A17 Pro 仿生芯片，采用 3nm 制程工艺，性能与能效全面提升。6.3 英寸超视网膜 XDR 显示屏，支持最高 2000 尼特峰值亮度。后置 4800 万像素主摄，支持 4K 120fps 视频录制。钛金属中框更轻更坚固，续航持久。',
 'https://picsum.photos/seed/iphone16pro/600/600', 7999.00, 8999.00, 500, 12345, 88000, 1),

(1, '华为 Mate 70 Pro 12GB+512GB 雅川青', '麒麟芯片 | 超聚变影像 | 鸿蒙系统',
 '华为 Mate 70 Pro搭载新一代麒麟芯片，集成超聚变影像系统，支持 AI 隔空操控。6.9 英寸 OLED 全面屏，1.5K 分辨率，120Hz 流畅刷新。内置 6100mAh 电池，支持 100W 有线快充与 50W 无线快充。',
 'https://picsum.photos/seed/mate70pro/600/600', 6499.00, 6999.00, 320, 8765, 62000, 1),

(1, '小米 15 Pro 16GB+512GB 岩石灰', '骁龙8至尊版 | 徕卡光学 | 6100mAh',
 '小米 15 Pro 搭载骁龙 8 至尊版芯片，3nm 制程。配备徕卡三摄系统，支持 5 倍无损变焦。6100mAh 超大电池 + 90W 快充，续航无焦虑。16GB 运行内存，多任务流畅。',
 'https://picsum.photos/seed/xiaomi15pro/600/600', 5299.00, 5599.00, 450, 6543, 45000, 1),

(2, 'Apple MacBook Air M4 13英寸 16GB+512GB', 'M4 芯片 | 18小时续航 | 无风扇设计',
 'MacBook Air 搭载全新 M4 芯片，性能相比上一代大幅提升，同时保持极致轻薄。13.6 英寸 Liquid Retina 显示屏，500 尼特亮度。全天候续航长达 18 小时，无风扇设计带来绝对安静的使用体验。',
 'https://picsum.photos/seed/macbookair/600/600', 9499.00, 10999.00, 200, 4210, 38000, 1),

(2, '联想 ThinkPad X1 Carbon AI 2025', '酷睿Ultra7 | 32GB | 1TB固态',
 'ThinkPad X1 Carbon AI 2025 搭载酷睿 Ultra 7 处理器，集成 NPU 单元。14 英寸 2.8K OLED 屏幕，120Hz。32GB 内存 + 1TB 固态硬盘，经典碳纤维机身，1.09kg 极致便携。',
 'https://picsum.photos/seed/thinkpadx1/600/600', 12999.00, 14999.00, 150, 1890, 22000, 1),

(2, '戴尔 U2723QE 27英寸 4K 显示器', 'IPS Black | 4K分辨率 | Type-C 90W',
 '戴尔 U2723QE 采用 IPS Black 面板，4K UHD 分辨率，2000:1 对比度，支持 98% DCI-P3 色域覆盖。内置 RJ45 网口与 USB 集线器，Type-C 接口支持 90W 反向供电，一线连接。',
 'https://picsum.photos/seed/dellmonitor/600/600', 3299.00, 3999.00, 260, 3310, 28000, 1),

(3, '美的 变频空气循环扇 落地静音版', '无叶静音 | 大风量 | 遥控定时',
 '美的空气循环扇采用无叶设计，出风柔和不伤皮肤。直流变频电机运行噪音低至 28 分贝，支持 12 档风速调节与 7.5 小时定时。广域摇头送风，覆盖全屋。',
 'https://picsum.photos/seed/airfan/600/600', 799.00, 1099.00, 600, 9876, 62000, 1),

(3, '海尔 500L 十字四门冰箱', '风冷无霜 | 一级能效 | 超薄嵌装',
 '海尔 500L 十字四门冰箱采用风冷无霜技术，食材保鲜不结冰。一级能效标识，综合耗电量低。超薄机身设计可嵌入橱柜，节省空间。内置智能变温区，按食材需求精准控温。',
 'https://picsum.photos/seed/fridge/600/600', 4599.00, 5299.00, 180, 4520, 39000, 1),

(3, '戴森 V12 Detect Slim 轻量无绳吸尘器', '激光探测 | 无绳轻量 | 全能基站',
 '戴森 V12 Detect Slim 采用激光探测技术，自动识别并统计灰尘数量。无绳轻量设计，整机仅 2.2kg，长时间使用不累手。配备全能清洁基站，可自动充电和倾倒尘桶。',
 'https://picsum.photos/seed/dysonv12/600/600', 3699.00, 4299.00, 220, 6540, 51000, 1),

(4, '优衣库 摇粒绒外套 男款 冰川灰 L码', '轻暖摇粒绒 | 防风 | 简约百搭',
 '优衣库摇粒绒外套采用柔软细腻的摇粒绒面料，轻暖舒适。立领防风设计，有效阻挡冷风。简约纯色版型，适合日常通勤与户外休闲穿着。',
 'https://picsum.photos/seed/fleece/600/600', 199.00, 299.00, 1200, 23456, 88000, 1),

(4, ' Nike Air Force 1 07 运动鞋 白', '经典鞋型 | 复古百搭 | 全尺码',
 'Nike Air Force 1 07 延续经典复古鞋型，鞋面采用优质皮革材质，透气孔设计提升舒适度。Air 缓震中底带来柔软脚感。全尺码在售，是日常穿搭的百搭单品。',
 'https://picsum.photos/seed/airforce1/600/600', 799.00, 899.00, 800, 19876, 95000, 1),

(5, '智利车厘子 JJ级 2斤装', '新鲜直达 | 果径28mm+ | 顺丰包邮',
 '智利车厘子 JJ 级，果实直径 28mm 以上，果肉紧实、甜度高、汁水充足。冷链空运直达，全程冷链保鲜。颗颗精选，坏果包赔。',
 'https://picsum.photos/seed/cherries/600/600', 89.00, 129.00, 2000, 45678, 120000, 1),

(5, '阳澄湖大闸蟹 蟹卡 4两公3两母 8只', '产地直发 | 鲜活到家 | 提蟹券',
 '阳澄湖大闸蟹，产地直发，规格 4 两公 3 两母共 8 只。膏满黄肥，鲜香可口。蟹卡形式发放，有效期三个月，可随时兑换。',
 'https://picsum.photos/seed/crab/600/600', 498.00, 698.00, 300, 12345, 76000, 1),

(6, '《深入理解计算机系统》原书第3版', '计算机科学经典教材 | 配套习题解析',
 '《深入理解计算机系统》(CS:APP) 是计算机科学领域的经典教材，从程序员视角深入讲解计算机系统的各个层次。涵盖数据表示、汇编语言、存储层次、并发等核心内容，是通往计算机科学殿堂的必读之书。',
 'https://picsum.photos/seed/csapp/600/600', 139.00, 128.00, 500, 8765, 43000, 1),

(6, '《人类简史》赫伯特·乔治·威尔斯的智识巅峰', '豆瓣9.1高分 | 历史与文明',
 '《人类简史》以宏大视角审视人类从认知革命到科学革命的发展历程。作者以智识的笔触，探讨智人如何凭借虚构故事的能力建立大规模协作秩序，重塑了人类文明的走向。',
 'https://picsum.photos/seed/sapiens/600/600', 59.00, 68.00, 800, 23456, 99000, 1);

-- 默认收货地址（演示用户 demo，user_id = 2）
INSERT INTO `t_address` (`user_id`, `receiver`, `phone`, `province`, `city`, `district`, `detail`, `is_default`) VALUES
(2, '贺柯榛', '13900000001', '湖北省', '武汉市', '洪山区', '铁机新居16栋 3单元 1202室', 1);