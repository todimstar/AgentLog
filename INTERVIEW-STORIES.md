# AgentLog 面试故事集

> 每个故事独立自包含——不依赖其他故事、不假设你记得任何背景。
> 每个故事 = 背景(这课做什么) → 遇到了什么问题(为什么是问题) → 怎么解决的(逐步) → 学到什么。
> 面试时选 2-3 个讲,每个 3-5 分钟。

---

## 故事 1:Feed 列表的 N+1 问题怎么防（L07）

### 背景

L07 要做 Feed 列表页——首页列出一屏帖子卡片,每张卡片上要显示分区名（如「开发日志」）、作者名。数据库里 `post` 表只存了 `channel_id` 和 `owner_user_id`(两个数字),分区名在 `forum_channel` 表,作者名在 `user_account` 表。

所以查 Feed 的流程是:查帖子 → 拿每行的 channel_id 去查分区名 → 拿 owner_user_id 去查作者名 → 拼成卡片。

### 问题:什么是 N+1,为什么会发生

N+1 = 「1 次查列表 + 列表里每条再各查 1 次关联数据」。Feed 一页 20 条:

```
错误做法（N+1）:
  1. SELECT ... FROM post ... LIMIT 20     ← 1 次,得到 20 行
  2. 对第1行: SELECT name FROM forum_channel WHERE id=3   ← +1
  3. 对第2行: SELECT name FROM forum_channel WHERE id=3   ← +1 (其实和上行 channel 相同,但没缓存)
  4. 对第3行: SELECT name FROM forum_channel WHERE id=7   ← +1
  ... 20 次查分区 + 20 次查作者
  总共:1 + 20 + 20 = 41 条 SQL
```

每次 SQL 都是一次网络往返 + 一次索引查找。Feed 量一大,数据库连接池被打爆,页面加载变慢——这就是 N+1。

```
N+1 可视化:
                    查 Feed (1 条 SQL)
                   /  |  |  \  ...  \
                行1  行2 行3  行4  行20
                 |    |   |    |     |
             查分区 查分区 查分区 ... (20 条 SQL)
             查作者 查作者 查作者 ... (20 条 SQL)
             
总计 41 次 DB 往返 ← N+1 问题
```

### 解决方案:收集 id → 一次批量 IN → 内存建 Map 分组

```
正确做法（恒 3 条 SQL）:
  1. SELECT ... FROM post ... LIMIT 20               ← 查帖子
  2. SELECT id,name FROM forum_channel               ← 只查需要的分区
       WHERE id IN (3,7,1,...)                       ← 从第1步结果里收齐的所有 channelId
  3. SELECT id,display_name FROM user_account        ← 只查需要的作者
       WHERE id IN (5,2,9,...)                       ← 从第1步结果里收齐的所有 authorId
  4. 内存组装:channelMap.get(row.channelId) → 分区名   ← 不查库,用 Map.get()
             authorMap.get(row.ownerUserId) → 作者名  ← O(1) 内存查找

可视化:
                    查 Feed (1 条 SQL)
                   /                 \
           收齐 channelIds      收齐 authorUserIds
               ↓                     ↓
        IN 批量查分区 (1 条)    IN 批量查作者 (1 条)
               ↓                     ↓
            内存 Map              内存 Map
               ↘                     ↙
              逐卡拼装,零额外 DB 往返
```

关键步骤:
1. 查完帖子列表后,**不是逐条查**分区,而是收集所有行的 `channelId` 放进一个 `Set<Long>`
2. 一条 SQL: `SELECT * FROM forum_channel WHERE id IN (3,7,1,5,9,...)`——所有分区名一次取回
3. 结果放进 `Map<Long, ChannelView>`
4. 逐卡拼装时 `channelMap.get(row.getChannelId())`——内存 O(1) 查找,不再查数据库

L21 标签批量查也复用这个模式——收齐 tagId,一次 IN,内存分组。

### 面试自述

> Feed 列表每张卡片上要显示分区名和作者名,但我们的数据模型里 post 表只存了外键 id。如果循环里逐条查——一页 20 张卡就是 1+20+20=41 条 SQL。我的解决方案是收齐所有 channel_id 放进 Set,一条 IN 查询全部取回,再放进内存 Map。拼装卡片时直接 map.get()——O(1) 内存查找,不用再查库。**整页 Feed 恒定 3 条 SQL,不随卡片数增长。后来 L21 标签批量查也复用了这套「收齐 id→IN→内存分组」的模式。**

### 学习点

- N+1 的本质:「查列表(1) + 每条逐查关联(N) = 1+N 次 SQL」,卡片越多 SQL 越多
- 解决方法:收齐 id→批量 IN→Map 分组,恒定次数,不随长度增长

---

## 故事 2:CQRS 读写分离——同一张 post 表,两套模型（L07）

### 背景

L06 建了 `content` 模块——它管发帖(建草稿、发布、快照复制)。里面有一张 `post` 表,对应 Java 类 `PostDO`(全字段 + 乐观锁 + `@Version` + `@TableId`)。

L07 要做 Feed 列表和单篇详情——这两个是「读帖子」,不是「写帖子」。

### 问题:为什么不能直接复用 content 的 PostDO？

1. **PostDO 太重**——它有 20+ 字段(乐观锁 version、各种缓存列、统计列、外键列),读 Feed 只需要 13 个卡片字段
2. **PostDO 在 content 模块**——content 是写模型,forum 是读模型。forum `import` content 的内部类 = **Modulith 边界违规**(ArchUnit 测试会红)
3. **职责混了**——如果 forum 直接 import PostDO 来读,那 forum 也间接绑定了 content 的事务/乐观锁/写入规则,耦合度爆炸

### 解决方案:同一张 post 物理表,两套 Java 模型

```
content 模块（写）                    forum 模块（读）
┌─────────────────────────┐        ┌─────────────────────────┐
│ PostDO                  │        │ PostFeedRow             │
│  - id                   │        │  - id                   │
│  - ownerUserId          │        │  - titleCache           │
│  - channelId            │        │  - summaryCache         │
│  - visibilityStatus     │        │  - channelId            │
│  - currentPublishedVer  │        │  - ownerUserId          │
│  - titleCache           │        │  - commentCount         │
│  - summaryCache         │        │  - likeCount            │
│  - likeCount            │        │  - ... (只 13 个卡片字段) │
│  - commentCount         │        │                         │
│  - collectionCount      │        │ PostFeedMapper.xml      │
│  - hotScore             │        │  SELECT 显示列 FROM post │
│  - isPinned             │        │  不写 SELECT *          │
│  - isEssence            │        │                         │
│  - version (@Version)   │        │ 零 import content 的类   │
│  - createdAt            │        │ ModularityTest 绿 ✓     │
│  - updatedAt            │        └─────────────────────────┘
│  ... (20+ 字段)          │
│                         │
│ PostMapper.java         │
│  extends BaseMapper     │
│  白送 insert/updateById │
│                         │
│ PostFeedMapper.xml      │
│  写操作走 LambdaQuery   │
└─────────────────────────┘

同一张 post 物理表 ← MySQL
```

同理,`forum_channel` 表:`ChannelView`(forum 自建投影,只读)vs `ForumChannelDO`(content 自建投影,也读)——**各模块各建各的投影,互不 import**。

### 面试自述

> CQRS 就是同一张表用两套模型。content 模块的 PostDO 管写,全字段 + 乐观锁 + 事务。forum 模块的 PostFeedRow 只管读,只有 13 个卡片字段,对应一条手写 XML 的 SELECT。两个模块互不 import,代码层级零耦合。读侧的变迁不会拖慢写侧,写侧的事务锁不会阻塞读侧——这就是命令和查询的分离。同时满足 Modulith 边界不违规。**

### 学习点

- **写模型**:全字段 + 乐观锁 + 事务保障 = 适合增删改
- **读模型**:投影(只取需要的列) + 聚合 JOIN + 排序 = 只读、快、隔离
- 同一张物理表不属于任何一个模块——模块各自拥有自己关心的那个「视图」而不是那张表

---

## 故事 3:B站评论两层模型——逻辑无限回复,物理永远两层 🔴 首推

### 背景

L08 要给帖子评论区,目标是 B站式的体验:你可以回复楼主,也可以回复别人的回复(A→B→C 链式对话,像聊天一样),但**物理数据永远只有两层深度**——B站评论区永远不出现三级缩进。为什么?B站评论区是给手机屏看的,深度越深缩进越窄,三级以后就只剩一条竖线,可读性崩溃。

### 核心区分:你要禁的不是「回复回复」,是「缩进加深」

```
✅ B 站的「无限回复」(逻辑上能一直接话,物理上永远两层):
一级 #1: 楼主评论
 ├ #2: "我觉得不错"        — 二级,回复楼主
 ├ #3: "回复 @二楼: 确实"  — 二级,回复#2,但结构上还是二级!没有产生三级缩进
 ├ #4: "回复 @三楼: 对"    — 二级,回复#3,还能接着回下去!
 └ #5: "回复 @四楼: 是啊"  — 二级,永远二级

❌ 真正要禁的「三级」(缩进加深):
一级 #1: 楼主评论
 └ #2: "我觉得不错"
    └ #3: "回复 @二楼"
       └ #4: "回复 @三楼"    ← 缩进加深,移动端只剩一条竖线
```

**B站做到这个效果靠的是「扁平化」,不是「拒绝」。**

### 初版 bug:把「回复回复」当成三级拒绝了

初版用 `parent_comment_id`(直接父)来判断层级:
- 父不存在 → 一级评论
- 父是一级 → 二级回复
- 父是二级 → **抛错「禁止三级」**

结果:楼主发了一级,#2 回复楼主(二级),然后没人能回复 #2 了——因为 #2 是二级,`parent.depth != 0` 会抛错。**这不是 B站,是远古论坛。**

### 正确方案:加 root_comment_id(楼)——用扁平化消灭三级的存在可能

在 `comment` 表里保留 `parent_comment_id`(直接回复谁),但**新增 `root_comment_id`(这一条属于哪层楼)**:

```
comment 表(V006,最终版):
  id              BIGINT    自增主键
  post_id         BIGINT    挂在哪篇帖下
  author_user_id  BIGINT    评论人
  root_comment_id BIGINT    楼——指向这层楼的「楼主」评论
                             一级评论 root=自己(插入后回填)
                             二级回复 root=所属一级的 id
  parent_comment_id BIGINT   直接父——回复了谁
                             一级=null
                             二级=被回复的那条(可以是另一条二级!)
  reply_to_comment_id BIGINT @谁——展示"回复 @某人",不改结构
  depth           TINYINT   1=一级 / 2=二级
  content         VARCHAR
  status          VARCHAR   VISIBLE / DELETED(软删)

  CHECK(depth IN (1,2)) ← DB不变量,无论什么写入路径,depth 绝不可能=3
```

看一个真实链路,体会 `root` 怎么把「回复回复」压平:

```
一级 #1  root=1  parent=NULL  depth=1
 ├ #2  root=1  parent=1      depth=2  reply_to=1    "回复楼主"
 ├ #3  root=1  parent=2      depth=2  reply_to=2    "回复 @二楼"  ← 回复#2,#2 已是二级!
 └ #4  root=1  parent=3      depth=2  reply_to=3    "回复 @三楼"  ← 还能回复#3!
```

**核心逻辑在创建评论时:**

```java
// 目标是一级 → 楼就是它自己;目标是二级 → 楼是它的 root(继承楼上)
Long floor = target.getDepth() == 1 ? target.getId() : target.getRootCommentId();
comment.setRootCommentId(floor);  // 永远指向那层楼的楼主
comment.setDepth(2);              // 永远 = 2,客户端传什么来都没用
```

**不理解这一行就不会理解 B站模型**:回复 #3 时,`target` 是 #3(它的 depth=2),所以 `floor = #3.rootCommentId = #1`——新评论照样挂在 #1 楼下,depth 依然=2。

三级状态在创建时就**根本无法产生**:因为任何一个回复,不管它回复的是谁(一级还是二级),`root` 永远是「楼」——那层楼的一级评论。二级的 root 是它的楼,再为它回复的 root 还是那层楼**——root 列被继承,从不往下钻。depth 不累加,永远是 2。

### 那为什么还要 DB 的 `CHECK(depth IN (1,2))`？

既然应用层保证 depth 永远是 1 或 2,CHECK 看起来多余。但 CHECK 救的不是当前这条正确代码——是**未来的 bug**:管理员批量导入历史评论、另一个微服务写 comment 表、某次重构忘了扁平化逻辑……任何一个让 depth=3 溜进来的路径,DB 直接拒插。

> **「不变量下沉到数据库」**——不靠每个写入者的自觉,把铁律刻在表上。这就是深度防御(defense in depth)。

### 面试自述

> B站评论看起来能无限回复,但底层只有两层。关键是一列 `root_comment_id`——回复任一评论,新评论的 root 永远和父的 root 相同,而不是继续往下钻。三级缩进在存储模型里根本无法被表达,而不是产生后被拦截。数据建模就能消灭问题,不需要写抛错逻辑。数据库再用 CHECK depth IN(1,2) 给未来可能绕过的bug兜底。这是一个用建模做极致优化的例子。**

### 学习点

- 禁三级靠**建模**(root 继承,三级无法表达)而非靠**拦截**(检测到三级就报错)
- `root_comment_id` 把同楼所有评论拍平——前端按 root 分组就是两层树
- DB CHECK 是深度防御:给未来的 bug 兜底,不靠当前正确代码
- 关联:软删保楼层——硬删一级会让子回复的 root 外键指向不存在的行

---

## 故事 4:评论树躲 N+1 + 软删为什么不能硬删（L08 同课）

### 评论树躲 N+1

**问题**:一条评论要显示它的作者名,但 `comment` 表只存了 `author_user_id`,作者名在 `user_account` 表。外行做法:查 20 条评论(1 次) + 逐条查作者名(20 次) = 21 次 SQL。

**解决**:一条 JOIN 全拉:

```xml
<!-- CommentMapper.xml -->
SELECT c.id, c.root_comment_id, c.parent_comment_id, c.depth,
       c.author_user_id, u.display_name AS author_name,  ← JOIN 直接把作者名带回来
       c.content, c.status, c.like_count, c.created_at
FROM comment c
JOIN user_account u ON u.id = c.author_user_id            ← 一条 SQL 拉全
WHERE c.post_id = #{postId}
ORDER BY c.root_comment_id ASC, c.created_at ASC, c.id ASC ← 按楼排序
```

排序键是 `root_comment_id`:同一层楼的所有评论 root 值相同,排序后它们天然挨在一起。平铺数组就是「楼1 + 楼1的所有二级 → 楼2 + 楼2的二级们」。

前端拿到这个有序扁平数组,扫一遍按 root 分组就是两层树——**组树不是后端做的,是前端做的。后端恒定 1 条 SQL。**

```
排序后数组:
  [0] #1 root=1 depth=1 content="楼主"
  [1] #2 root=1 depth=2 content="回复楼主"       ← 同楼评论天然聚在一起
  [2] #3 root=1 depth=2 content="回复 @二楼"
  [3] #4 root=2 depth=1 content="另一条楼主"     ← 下一楼
  [4] #5 root=2 depth=2 content="回复二楼"

前端拿到后: root=1 → {楼#1 + [#2,#3]}, root=2 → {楼#4 + [#5]}
```

### 软删为什么不能硬删

删除一条一级评论有两种删法:

**硬删(错误)**:
```sql
DELETE FROM comment WHERE id=1;  -- 直接删掉
```
会触发两个问题:
1. **外键拒删**:`comment` 表有 `fk_comment_root FOREIGN KEY (root_comment_id) REFERENCES comment(id)`——如果 #2 #3 的 `root_comment_id=1`,而你要删 #1,MySQL 说「别人引用着你呢,不能删」。抛约束异常,删除失败。
2. **即使改外键级联(CASCADE):子回复 root 被 SET NULL → 这些回复的 `root` 丢失 → 前端无法判断它们属于哪一楼 → 「楼塌了」。**

**软删(正确)**:
```sql
UPDATE comment SET status='DELETED' WHERE id=1;  -- 只改状态列
```

行还在,root 外键仍有效,子回复的 `root_comment_id=1` 还能查到(虽然那条记录被标记为 DELETED 了)。前端拿到这条 `status=DELETED` 的评论,渲染「该评论已删除」占位。

> **面试一句话**:二级评论模型里,硬删一级会把子回复的 root 外键断掉——要么约束拒删,要么级联让子回复变孤儿。唯一安全的方式是软删改状态,留楼层结构。**

---

## 故事 5:开发库被赶工版迁移污染（L08 实战踩坑）🔴 首推

### 背景

本地 Docker MySQL 容器 `infra-mysql-1` 里有开发数据库 `agentlog`。之前有一条分支 `lessons/L07-onward` 是 AI 一口气跑完 L08→L25 的全自动施工——那次它跑完了全部 Flyway 迁移(V001→V017)。

现在重启 Claude 开了新分支 `L07-restart`,从干净的 L07 末重新开始做 L08。敲完新的 V006 `comment` 表脚本,启动后端……**报错了: Flyway ValidationException——V006 的 checksum 与 `flyway_schema_history` 里记录的不一致。**

### 调查

```
查看开发库的 flyway_schema_history 表:
┌──────────┬────────────────────────┬─────────┐
│ version  │ description             │ success │
├──────────┼────────────────────────┼─────────┤
│ 001-005  │ (我们自己的)            │ 1       │
│ 006      │ create comment          │ 1  ← 旧的分支执行过!
│ 007      │ create reaction ...     │ 1
│ 008-017  │ ...                     │ 1  ← V017!全自动分支跑完了
└──────────┴────────────────────────┴─────────┘

但我们的 V006 文件是新建的(带 root_comment_id + depth CHECK(1,2)):
  代码文件 V006 checksum = ABCD
  库里记录的 V006 checksum = XYZW(全自动分支的旧 V006)
  → 不一致 → Flyway 拒接启动
```

而且库里已有的 comment 表是旧结构——用了 `deleted` 布尔而不是 `status`,depth 是深度(1,2)但不是我们新写的字段名——**表和代码不匹配,即使绕过 Flyway 也跑步通。**

### 为什么测试没暴露？

测试用的是 **Testcontainers 临时全新 MySQL 容器**——每次跑测试都从 V001 干净跑到 V007,账本里没有旧分支的脏记录。测试全绿,但生产/开发库不同。

> **这就是「测试全绿 ≠ 启动能用」的心理模型:Testcontainers 给你的是一个全新真空库,但开发库已经被历史迁移污染了。Flyway 的 checksum 检查正是保护:它发现「同样的 V006 版本号被两个不同的脚本占过」,拒绝让你跑在不一致的表上。**

### 解决

```sql
-- 彻底重建开发库
DROP DATABASE agentlog;
CREATE DATABASE agentlog;
-- Flyway 下次启动自动从 V001 跑起
```

### 面试自述

> 做评论模块时启动后端, Flyway 报 ValidationException——本地 V006 脚本和库里记录的 checksum 不一致。调查发现开发 MySQL 库被之前另一个分支(AI 全自动施工)迁移到了 V017,里面有一张结构完全不同的旧 V006 表。但我的测试全绿——因为 Testcontainers 每次创建新容器,跑的是干净的空库,测不出已被其他分支污染的账本。以后每次加迁移,都应该在真实开发库也验证一次。**

### 学习点

- Flyway 靠 `flyway_schema_history` 记账,每个版本存一份 checksum,改文件会导致校验失败
- 开发数据库也是一种状态,会被其他分支的迁移污染
- Testcontainers 用临时新库测迁移能测出脚本写错,但测不出「与已有账本冲突」

---

## 故事 6:点赞 toggle——INSERT IGNORE 一步判加删 🔴 首推

### 背景

L09 要给帖子加「点赞」和「收藏」功能。点赞按钮点一下加赞,再点一下取消点赞——同一个按钮,每次点做的事情相反。这是 toggle(开关)模式。

我们的点赞记录存在 `reaction` 表里:

```
reaction 表(V007):
  id          BIGINT  PK
  user_id     BIGINT  谁点的赞(= user_account.id)
  target_type VARCHAR  POST(赞帖) 或 COMMENT(赞评论)
  target_id   BIGINT  被赞的对象 id
  created_at  DATETIME

  ⭐ UNIQUE KEY uk_reaction_user_target (user_id, target_type, target_id)
  这个唯一键保证:同一用户对同一目标只能有一条 reaction 记录
  比如 Alice(user_id=1) 对 post#1(target_id=1, target_type='POST')
  在整张表里只能存在一行,绝不可能有两行
```

### 外行做法:先查后插——两步操作,有时间空隙

```
第一步:SELECT * FROM reaction WHERE user=1 AND target='POST' AND id=5
   → 没找到!说明没赞过 → 做第二步
第二步:INSERT INTO reaction(user_id, target_type, target_id) VALUES (1,'POST',5)
```

**为什么这个有 bug？** 因为它把「检查是否存在」和「写入」分成了两步,中间有空隙。并发时:

```
Alice 手快连点两次 ♡ 按钮,浏览器发出两个 HTTP 请求:

时间轴 →
请求A                                    请求B
─────────────────────────────────────────────────────────
SELECT ... WHERE user=1 & target=5
→ 查无!这是新赞,走 INSERT 分支
                                          SELECT ... WHERE user=1 & target=5
                                          → 查无!也是新赞(请求A 还没 INSERT 完)
INSERT INTO reaction(1,'POST',5)
→ 插入成功 ✓
                                          INSERT INTO reaction(1,'POST',5)
                                          → 没有唯一键就也插成功!
                                          但若有 UNIQUE KEY:
                                          → 直接抛异常 DuplicateKeyException
                                          用户看到 500 错误!
```

**两步操作,并发下要么出脏数据(没唯一键→重复赞),要么出 500 错误(有唯一键→抛异常)。**

### 正确做法:INSERT IGNORE 一步完成判断和插入

`INSERT IGNORE` 是 MySQL 的一条指令——和普通 INSERT 的区别是:遇到唯一键冲突时不抛异常,而是**静默跳过**,返回「0 rows affected」。

```sql
-- Alice 第一次点赞 post#1:
INSERT IGNORE INTO reaction (user_id, target_type, target_id, created_at)
VALUES (1, 'POST', 1, NOW());
→ 唯一键未冲突 → 插入成功 → MySQL 返回 "1 row affected"

-- Alice 再点同一个按钮(第二次):
INSERT IGNORE INTO reaction (user_id, target_type, target_id, created_at)
VALUES (1, 'POST', 1, NOW());
→ 唯一键冲突!被 IGNORE 跳过 → MySQL 返回 "0 rows affected"
```

**`affectedRows`(受影响行数)就是 toggle 的判据:**

```java
int affectedRows = reactionMapper.insertIgnore(reaction);
// insertIgnore 是我们在 ReactionMapper.xml 里写的:
//   INSERT IGNORE INTO reaction (...) VALUES (...)

if (affectedRows == 1) {
    // 之前没赞 → 这次加上! like_count +1
    reactionMapper.bumpPostLikeCount(targetId, 1);   // UPDATE post SET like_count = like_count+1
    return new ToggleStateResponse(true, newCount);   // {active: true, count: 5}
} else {
    // affectedRows == 0 → 之前已赞 → 这次取消!
    // 1. 删掉那条 reaction 记录
    reactionMapper.delete(userId, targetType, targetId);
    // 2. like_count -1
    reactionMapper.bumpPostLikeCount(targetId, -1);   // UPDATE post SET like_count = like_count-1
    return new ToggleStateResponse(false, newCount);  // {active: false, count: 4}
}
```

```
流程图:

点击「♡」   →   INSERT IGNORE INTO reaction(user_id, target_type, target_id)
                      │
                  唯一键冲突?
               ┌─── 否 ─── 插入成功, affectedRows=1
               │              → 新赞! like_count +1
               │              → response: {active: true, count}
               │
               └─── 是 ─── 被 IGNORE, affectedRows=0
                              → 取消! DELETE 记录 + like_count -1
                              → response: {active: false, count}
```

和「先查后插」对比:
| 维度 | 先查后插 | INSERT IGNORE |
|---|---|---|
| 操作步骤 | SELECT(1步) + INSERT/DELETE(1步) = 2步 | **INSERT IGNORE 1 步 = 判断 + 写入** |
| 并发同一用户 | 两个请求同时查无→两条 INSERT(脏数据) | DB 层唯一键保证只有一条能插入,另一条 affectedRows=0 |
| 错误处理 | 唯一键冲突=抛异常 500 | 静默跳过,不影响用户 |
| 代码行数 | if (exists) delete +1 else insert -1 | 看 affectedRows == 1 ? 加 : 删 |

**收藏 toggle 完全同构**——只是表不同（`collection_record` 只有 user_id+post_id,不需要 target_type）、计数列不同（`collection_count`）。

### 计数防负:GREATEST 钳到 0

正常操作下计数不会变负——点赞 +1、取消 -1,配对操作。但万一数据不一致(比如手动 SQL 把 `like_count` 改成了 0 但 reaction 记录还在):

```sql
-- 没有 GREATEST 的 UPDATE: 0 + (-1) = -1 ← 负赞数!
UPDATE post SET like_count = like_count + (-1) WHERE id = 1;

-- 有 GREATEST 的 UPDATE: GREATEST(0, 0+(-1)) = GREATEST(0, -1) = 0
UPDATE post SET like_count = GREATEST(0, like_count + (-1)) WHERE id = 1;
```

`GREATEST(0, x)` 取两个参数的最大值——如果 x 是 -1,结果仍是 0。相当于 `Math.max(0, x)`。这是防御性写法:**不依赖所有写入者都正确处理,万一 count 已经是 0 还要 -1,不会出现 -1 赞。**

### 面试自述

> 点赞按钮要 toggle——同一接口点加取消。我先看「先查后插」——先 SELECT 有没有再 INSERT 或 DELETE,这是两步操作,有时间空隙。并发下同一个用户连点两次,两个请求都"查无",然后都 INSERT→要么没唯一键时出脏数据,要么有唯一键时抛异常 500。改 INSERT IGNORE——一句下去,看返回的 affectedRows 是 1(新赞 count+1)还是 0(已赞过→DELETE+count-1)。判断和写入一步完成,唯一键是并发兜底。最后计数用了 GREATEST(0, count+delta)——万一数据不一致 count 已为 0 还减,GREATEST 钳到 0 不变负。喜欢/收藏两套功能复用完全同构。**

### 学习点

- `INSERT IGNORE` = 唯一键冲突时静默跳过、返回 0 而非抛异常
- `affectedRows` = MyBatis 返回的受影响行数,INSERT 后为 1(插成功)或 0(被 IGNORE)
- 唯一键是并发物理兜底:不管多少请求并发,DB 保证唯一键值不重复
- `GREATEST(0, x)` = 取最大值,防负数——防御性写法,弥补数据不一致

---

## 故事 7:跨模块操作的三种规则（L07→L09 三层递进）

### 背景

Modulith 架构下,每个模块只 import 自己的类和 `shared`。跨模块操作不能随意 import 对方的 Mapper/DO。但业务上 forum 经常需要读 content 的数据(帖子、评论),偶尔也需要写对方拥有的数据(归属关系)。怎么跑通？

### 三层规则——根据「读还是写」和「谁的数据」分支

```
                       跨模块操作「什么」
                     /                    \
                  只读                     写
                  /                          \
         自建投影直查物理表             是「我的派生列」吗?
         (零 import 对方)               /               \
         eg. forum 读 channel         是(拥有)           否(别人的核心数据)
         forum 读 post 详情       自建投影直写物理表       必须走对方 Facade
         forum 读 comment         (零 import 对方 DO)    (对方根包暴露)
                                  eg. forum 写           eg. AI 投稿到
                                  comment_count          content 草稿要调
                                  like_count             ContentFacade
                                  collection_count
```

**规则①:只读对方数据 → 自建投影直查物理表(零 import 对方)**

forum 要显示 channel 名、post 详情、comment 列表——这些都是「读 content 拥有的数据」。做法:**forum 自建一套读取专用的 DTO + Mapper + XML**,直接读物理表,但不 import content 的任何类。

```
forum 自建投影:
  ChannelView(record)           → ChannelView → PostFeedMapper.selectChannelsByIds()
  PublicPostVersionRow(DO)     → 读 post.post_version
  PublicPostBlockRow(DO)       → 读 post.post_version_block

这些都归 forum,不 import content 的类
```

**规则②:写自己的派生列 → 自建投影直写物理表**

post 表的 `comment_count`、`like_count`、`collection_count`——这些列虽然在 post 物理表中,但它们是 **forum 维护的互动统计**。forum 用自己的 Mapper 直写这些列,不 import content 的 PostDO。

```xml
<!-- forum 的 CommentMapper.xml: -->
UPDATE post SET comment_count = comment_count + #{delta} WHERE id = #{postId}

<!-- forum 的 ReactionMapper.xml: -->
UPDATE post SET like_count = GREATEST(0, like_count + #{delta}) WHERE id = #{postId}

<!-- forum 的 CollectionRecordMapper.xml: -->
UPDATE post SET collection_count = GREATEST(0, collection_count + #{delta}) WHERE id = #{postId}
```

为什么合规？**「物理表所在模块 ≠ 每列归属的模块」**——post 表里的内容字段(title, summary, content 指针)归 content,互动计数(comment_count, like_count, collection_count, hot_score)归 forum。这叫**自治派生列**(自己的计数自己维护)。

**规则③:写对方的核心数据 → Facade**

当 forum 或别的模块需要往 content 的 `draft` 表里插 AI 投稿——这不是 forum 自己的派生列,是 content 的核心数据(草稿).这时必须走 `ContentFacade.createContribution()`.Facade 是对方模块根包暴露的官方写入通道,内部封装写入规则。

### 面试自述

> Modulith 架构下跨模块操作我们按「读还还是写」「谁的数据」分了三种规则。只读:自建 DTO + Mapper 直查物理表,不导入对方类。写自己派生列:统计列(comment_count/like_count)虽然物理上在 post 表里,但它们是 forum 维护的互动计数——forum 用自己的投影更新,不 import 对方的 DO。真正写对方核心数据才走 Facade(如 AI 投稿走 ContentFacade)。三种规则越往外层越严格。**

---

## 故事 8:CSRF 死锁——入口豁免（L05）

### 背景

L05 引入 CSRF 防护——所有 POST/PUT/DELETE 请求需要带 CSRF token(存在 XSRF-TOKEN Cookie 里,请求头带 X-XSRF-TOKEN)。Spring Security 负责拦截没 token 的请求。

但登录接口本身就是 POST!用户去登录 → 没有 CSRF token(还没登录,没 cookie) → 被 CSRF 拦截 → 永远登录不了。

### 解决

三个入口加入 `.ignoringRequestMatchers`,豁免 CSRF:

```java
// ApiSecurityConfiguration.java
.csrf(csrf -> csrf
    .ignoringRequestMatchers(
        "/api/v1/web/auth/register",
        "/api/v1/web/auth/login",
        "/api/v1/web/csrf"))     // CSRF token 获取接口自己也得豁免
```

**Postman 集合里每次写操作前主动 GET `/web/csrf` 刷新 token,存进集合变量,后续请求引用**——这是为了 token 过期不导致 403。

### 关联:怎么理解 CSRF？

```
正常请求:
  浏览器 POST /web/login  [Cookie: JSESSIONID=abc; XSRF-TOKEN=xyz]
  → 后端比对 Cookie 里的 XSRF-TOKEN 和 Header X-XSRF-TOKEN  → 一致 → 放行

恶意网站伪造:
  恶意站 <form action="http://agentlog.com/api/v1/web/delete-me" method="POST">
  → 浏览器自动带 Cookie(JSESSIONID 验证通过)
  → 但恶意站不知道 XSRF-TOKEN 的值,Header 里没带 token
  → 后端比对不一致 → 拒绝
```

CSRF token 放在 JS 可读的 Cookie 里(不设 HttpOnly),前端读出后再放到请求头。恶意网站不知道这个 token,所以伪造不了。

---

## 面试自述模板

选 2-3 个故事串成 3-5 分钟:

```
我用 Spring Boot + Modulith + MySQL 做了个社区项目。最有意思的有三点。

第一是 B站评论模型。要能回复回复但不能树状缩进——不能用拦截三级的方式做,
我引入 root_comment_id 列让每个回复的 root 永远等于它所属那层楼,深度永远不超过二。
三级在存储模型里无法表达——建模消灭问题不是检测出问题。数据库再加 CHECK depth IN(1,2)
给未来可能绕过的代码兜底。

第二是点赞 toggle。要同一个功能点加再取消,本能是先查有没有再决定插还是删——但
这是两步,有时间空隙,并发下同一用户连点两次都查到'没有'就重复赞。改 INSERT IGNORE,
一句 SQL 同时做判断和写入——返回的 affectedRows 是 1 就往上加,0 就往下减。
唯一键保证了并发下也不可能产生重复。

第三是一个设计演化——跨模块操作按'读我的数据'还是'写你自己的数据'分了三种规则:
读只建投影直查、写自己派生列也直写,只有写对方核心数据才走 Facade。
这比所有跨模块都走 Facade 的设计高效得多,边界也好检测(ModularityTest 自动扫)。
```
