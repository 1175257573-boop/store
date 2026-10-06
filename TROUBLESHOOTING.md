# 编译期与运行期踩坑记录

## 1. MySQL 5.7 客户端字符集（必现）

**现象**：导入 SQL 时报
```
ERROR 1406 (22001) at line 170: Data too long for column 'nickname' at row 1
```

**根因**：MySQL 5.7 的 `mysql.exe` 默认按本地编码（Windows 中文环境为 GBK）
解析 SQL 文件。`VARCHAR(50)` 在 utf8mb4 下按字符计数，但客户端传入的 GBK
双字节序列被当成字符处理，一个中文算 2 个字符，`系统管理员` 6 个字就撑爆了 50。

**解法**：显式指定客户端字符集
```bash
mysql -uroot -p123456 --default-character-set=utf8mb4 < ecommerce.sql
```

**验证**：`SELECT name FROM t_product LIMIT 3;` 中文正常显示。

---

## 2. MyBatis-Plus starter 与 Spring Boot 3 不兼容

**现象**：Spring 容器启动即崩
```
java.lang.IllegalArgumentException:
  Invalid value type for attribute 'factoryBeanObjectType': java.lang.String
  at FactoryBeanRegistrySupport.getTypeForFactoryBeanFromAttributes
```

**根因**：`mybatis-plus-boot-starter` 传递引入 mybatis-spring 2.x，
它以字符串形式注册 `FactoryBean` 的 `factoryBeanObjectType` 属性。
Spring Boot 3 / Spring 6 改为从该属性直接取 `Class<?>`，遇到 String 就抛
`IllegalArgumentException`。

**解法**：改用专门适配 Spring Boot 3 的 starter
```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.7</version>
</dependency>
```

---

## 3. Jackson2JsonRedisSerializer 没有 getObjectMapper()

**现象**
```
找不到符号: 方法 getObjectMapper()
  位置: 类型 Jackson2JsonRedisSerializer<java.lang.Object> 的变量 jsonSerializer
```

**根因**：Spring Data Redis 3.x 重构了该类，移除了实例方法 `getObjectMapper()`。

**解法**：自建 `ObjectMapper` 后用双参构造器传入
```java
Jackson2JsonRedisSerializer<Object> s =
    new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);
```

**注意**：不能复用 Spring MVC 配置的 ObjectMapper。MVC 的配了
`Long -> String` 转换和 `NON_NULL` 包含策略，前者会让缓存里的 ID 变字符串，
后者会丢字段。缓存序列化需要独立配置。

---

## 4. Git Bash 下 Maven 找不到 Launcher

**现象**
```
错误: 找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher
```

**根因**：`mvn` 脚本用 `$JAVA_HOME/bin/java` 拼 classpath。Git Bash 会把它
转成 `/e/devtools/...` 形式，Windows JVM 不认这种 POSIX 路径。

**验证过无效的做法**：
- 把 JDK 目录从 `jdk-17.0.11+9` 改名成 `jdk17`（路径里的 `+` 不是原因）
- 在 classpath 里写通配符 `-classpath .../plexus-classworlds-*.jar`（Java 不展开 glob）

**解法**：显式用 Windows 风格路径直接启动 Launcher
```bash
export JAVA_HOME="E:\\devtools\\jdk17"          # 必须是反斜杠的 Windows 路径
export MAVEN_HOME="E:\\devtools\\apache-maven-3.9.9"

"E:/devtools/jdk17/bin/java.exe" \
  -classpath "$MAVEN_HOME\\boot\\plexus-classworlds-2.8.0.jar" \
  -Dclassworlds.conf="$MAVEN_HOME\\bin\\m2.conf" \
  -Dmaven.home="$MAVEN_HOME" \
  -Dmaven.multiModuleProjectDirectory="$PWD" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
```

已封装为 `E:\devtools\mvnw.sh`。

**附带发现**：`maven.multiModuleProjectDirectory` 未设置时 Maven 会告警
并可能解析错误的工作目录，必须显式传 `-Dmaven.multiModuleProjectDirectory="$PWD"`。

---

## 5. download源 404

`dlcdn.apache.org` 只保留当前版本，指定历史版本会返回 196 字节的 404 页面。
解决办法二选一：
- 用 `repo1.maven.org/maven2/org/apache/maven/apache-maven/<版本>/`（中央仓库也有发行包）
- 或用 `archive.apache.org/dist/maven/maven-3/<版本>/binaries/`

> 教训：下载后先校验文件大小或 `unzip -t`，别直接拿去做下一步。
> 196 字节的 zip 在 `unzip` 时才会报错，容易误判成路径问题。

---

## 6. 无参 `lambdaQuery()` 导致 OGNL 求值失败

**现象**：调用 `baseMapper.selectOne(lambdaQuery()...)` 或 `IService.count(lambdaQuery())`
时抛
```
MyBatisSystemException: null
  Caused by: BuilderException: Error evaluating expression 'ew != null and ew.sqlFirst != null'
  Caused by: OgnlException: sqlFirst
  Caused by: MybatisPlusException: can not use this method for "getSqlFirst"
```

**根因**：`IService` 的**无参** `lambdaQuery()` 依赖 `ServiceImpl.currentModelClass()`
反射推断目标实体类型。推断失败时 Wrapper 的 `ew` 参数类型不对，
MyBatis 解析 `${ew.sqlFirst}` 表达式时调不通 getter。

**容易误判的地方**：同工程里 `Wrappers.<X>lambdaQuery()` 显式泛型写法完全正常，
所以现象是「同一个类里有的查询好使、有的报 500」，容易往 SQL 或表结构方向找。

**解法**：统一用显式泛型
```java
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

baseMapper.selectOne(Wrappers.<Address>lambdaQuery()
        .eq(Address::getId, id)
        .eq(Address::getUserId, userId)
        .last("LIMIT 1"));
```

> 排查手法：异常最外层是 `MyBatisSystemException: null`，信息量很少。
> 要往上翻到 `Caused by` 链底，才能看到 `getSqlFirst` 这个真正线索。
> 用 `grep -E "Caused by"` 一次性捞出整条链，别只看第一行。

---

## 7. Spring Security 默认拦截全部请求

**现象**：接口全部 401，日志里出现
```
Using generated security password: 11dba803-...
DefaultSecurityFilterChain - Will secure any request with [... UsernamePasswordAuthenticationFilter ...]
```

**根因**：只声明了 `PasswordEncoder` Bean 而没声明 `SecurityFilterChain`，
Spring Boot 会自动装配默认过滤器链：CSRF 校验 + 拦截所有 URL + 启用表单登录。

本项目认证走自研 JWT 拦截器，不需要 Security 过滤器链，但**必须显式关掉**。

**解法**
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}
```

---

## 8. Integer 与 int 用 == 比较触发拆箱 NPE

**现象**：新增地址接口偶发 500，堆栈指向业务代码但信息不直观。

**根因**：
```java
// dto.getIsDefault() 返回 Integer，前端不传该字段时为 null
boolean needDefault = count == 0 || BizConst.YES == dto.getIsDefault();
//                                                        ↑ Integer 与 int
//                                                          比较时自动拆箱 → NPE
```

**解法**：先判空
```java
boolean wantDefault = dto.getIsDefault() != null && dto.getIsDefault() == BizConst.YES;
```

> 注意 Java 的比较方向不影响结果：`int == Integer` 和 `Integer == int`
> 都会触发拆箱，**两个操作数必须都是包装类型且都不为 null** 才安全。

---

## 9. 缓存里的库存是陈旧值

**现象**：下单扣了 2 件库存，商品详情接口仍显示扣减前的数量；
取消订单后同样不恢复。直接查数据库发现值其实是对的。

**根因**：商品详情走了 Redis 缓存（TTL 30 分钟），下单和取消订单虽然都改了
数据库库存，但**取消订单那条路径漏了清缓存**；且详情接口直接返回缓存对象，
连 stock 字段也是 30 分钟前的快照。

**解法**：两处都要改
1. 所有改动库存的路径都要清对应商品的详情缓存（下单、取消都要）
2. 详情接口对易变字段回查数据库覆盖
```java
Product latest = baseMapper.selectById(id);
if (latest != null) {
    vo.setStock(latest.getStock());
    vo.setSales(latest.getSales());
    vo.setViewCount(latest.getViewCount());
}
```

> 判断标准：**用户会因为这个数字做决策，就必须实时**。
> 库存、价格、销量属于这类；分类名、商品图这些属于「展示型」数据，可以容忍缓存延迟。

---

## 10. PowerShell 工具的输出捕获

PowerShell 工具通道里 `& cmd` / `& mvn.cmd` 的 stdout 经常捕获为空，
且 `cmd /c` 被安全策略拦截。可靠做法是**让目标命令自己重定向落盘**，再用
Read 工具读文件。

---

## 11. 后台进程随 shell 会话退出被杀

用 `cmd &` 或 `(cmd &)` 启动的服务，在 Bash 工具调用结束后进程会被回收，
表现为「明明启动成功了，端口却不通」。

**解法**：用工具的 `run_in_background` 参数启动，且**命令本身要前台运行**
（不要在命令里再套 `&`）：
```
run_in_background: true
命令：java -jar xxx.jar
```

另外 `mvn clean` 删不掉正在运行的 jar（文件被占用），
需要先停掉服务再重新打包。
