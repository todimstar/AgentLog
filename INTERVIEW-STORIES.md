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

补充一个后来前端验收才暴露的小坑:toggle 接口只能告诉前端「刚点完以后 active 是 true 还是 false」,但用户刷新页面或重新进入帖子详情时,公开详情接口只返回全站总数,不会返回「当前登录用户是否已赞/已收藏」,所以按钮会回到默认的 ♡/☆。修法是把公共读模型和登录态私有状态拆开:详情页先调匿名可读的 `/public/posts/{id}` 拿内容和总数,再在已登录时静默调 `/web/reactions/state`、`/web/collections/state` 拿当前用户的 active 状态。这样游客仍能看公开帖子,登录用户重进页面也能恢复 ♥/★ 高亮。

### 计数防负:GREATEST 钳到 0

正常操作下计数不会变负——点赞 +1、取消 -1,配对操作。但万一数据不一致(比如手动 SQL 把 `like_count` 改成了 0 但 reaction 记录还在):

```sql
-- 没有 GREATEST 的 UPDATE: 0 + (-1) = -1 ← 负赞数!
UPDATE post SET like_count = like_count + (-1) WHERE id = 1;

-- 有 GREATEST 的 UPDATE: GREATEST(0, 0+(-1)) = GREATEST(0, -1) = 0
UPDATE post SET like_count = GREATEST(0, like_count + (-1)) WHERE id = 1;
```

`GREATEST(0, x)` 取两个参数的最大值——如果 x 是 -1,结果仍是 0。相当于 `Math.max(0, x)`。这是防御性写法:**不依赖所有写入者都正确处理,万一 count 已经是 0 还要 -1,不会出现 -1 赞。**

### 自述总结精华
> toggle模式就是两种，一个insert情况，直接用insert ignore，然后根据返回影响行数判断是新点赞去post+1还是取消点赞-1。一个是update情况，用GREATEST当max保底0。用insert ignore巧妙躲避先查后插异步重复陷阱

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

## 故事 9:Spring Boot 过滤器双重注册——为什么 @Component 反而是坑（L13）🔴 首推

### 背景

L13 要给命令行工具(CLI)和 AI 智能体(机娘)做认证。浏览器走 Session(L05 那条链),但 CLI/机娘没有 Cookie,只有一枚 Bearer 令牌。于是加了两条新的安全链:Chain 2 管 `/api/v1/cli/**`(认 owner 令牌)、Chain 3 管 `/api/v1/agent/**`(认机娘令牌)。每条链一个自写的 `OncePerRequestFilter` 验币。

按旧项目的习惯,过滤器都标了 `@Component` 注册成 Spring bean,让框架自动注入。阶段②③(只有 Chain 2 一个过滤器)一切正常。**阶段④加上 Chain 3 的过滤器后,测试突然红了 7 个**——凡是"带有效 owner 令牌访问 `/cli/**` 应得 200"的用例全变 401,而纯负向用例(无令牌/假令牌)反而正常。

### 问题:一个只该管 /agent/** 的过滤器,怎么把 /cli/** 拦了？

先要搞清一个大多数人没意识到的事实:**Spring 应用里有两层过滤器,而 `securityMatcher` 只管其中一层。**

```
HTTP 请求进来,穿过两层过滤器:

┌──────────────────────────────────────────────────┐
│  第一层:Servlet 容器级过滤器链(Tomcat 管)                       │
│    对【所有 URL】无差别生效,不认 securityMatcher、不认 @Order   │
│    ├─ CharacterEncodingFilter                                  │
│    ├─ ★ 被 @Component 的 OncePerRequestFilter(坑在这!)          │
│    └─ FilterChainProxy ← Spring Security 的总入口,本身也是个 Filter │
│           │                                                    │
│           ▼                                                    │
│      ┌────────────────────────────────────────┐              │
│      │ 第二层:FilterChainProxy 内部(Spring Security)          │ │
│      │   按 @Order 从小到大问每条 SecurityFilterChain:        │ │
│      │     Chain 3 @Order(0) securityMatcher=/agent/** 匹配? │ │
│      │     Chain 2 @Order(1) securityMatcher=/cli/**   匹配? │ │
│      │     Chain 1 @Order(2) 无 matcher = 兜底一切            │ │
│      │   命中的那条,才跑它 addFilterBefore 的验币过滤器        │ │
│      └────────────────────────────────────────┘              │
│           ▼                                                    │
│      DispatcherServlet → Controller → @RestControllerAdvice   │
└──────────────────────────────────────────────────┘
```

关键:`securityMatcher` 和 `@Order` **只在第二层(FilterChainProxy 内部)决定选哪条链**。但一个标了 `@Component` 的 `Filter` 类型 bean,Spring Boot 会**额外**把它包进 `FilterRegistrationBean`,注册到**第一层(容器级)**——这一层在 FilterChainProxy 之外、之前,对所有 URL 生效,**根本不看 securityMatcher**。

所以一个 `@Component` 的过滤器其实被注册了两次:

```
① 你在 Config 里 addFilterBefore 进指定链 ← 对,作用域限本链(第二层)
② Boot 看到它是 Filter 类型 bean,自动注册进容器级链 ← 错,全局(第一层)
```

事故链(这里有个反直觉的点,值得讲透):

容器级链是**有顺序**的。`FilterChainProxy` 注册在很靠前的位置(`SecurityProperties.DEFAULT_FILTER_ORDER = -100`),而 `@Component` 的普通 Filter 没指定 order 时默认排在**很后面**。所以执行顺序其实是「先 FilterChainProxy,后那个全局 agent 过滤器」。

那问题来了:既然 Security 先跑,请求不是已经在 FilterChainProxy 里被 Chain 2 认证成功了吗？后面那个 filter 再跑一次能有什么影响？——关键是 **FilterChainProxy 执行完选中的链后,会 `chain.doFilter()` 把请求交回容器级链继续往后走**,于是轮到那个全局 agent 过滤器:

```
/cli/whoami + 有效 owner 令牌  进来
   │
   ▼ 容器级 order=-100: FilterChainProxy 执行
   │    └─ 内部选中 Chain 2 → OwnerBearerFilter 验币成功 → SecurityContext 装入 owner 认证 ✓
   │    └─ Chain 2 走完,chain.doFilter() 交回容器级链
   │
   ▼ 容器级 order=MAX: 全局 agent 过滤器执行(被 @Component 塞进这一层)
   │    └─ 它【不看】SecurityContext 里已有认证,自己的逻辑是:
   │       读 Bearer → 拿 owner 令牌查 agent_acting_session(它只会查这张表)
   │       → 查无 → response.setStatus(401) + 写错误体 + 【不再 doFilter,链终止】
   │
   ✗ 401 被写死进 response,前面 Chain 2 的成功认证被这一步覆盖,DispatcherServlet 都到不了
```

**所以不是"走两次以最后一次为准"这种温和的覆盖,而是第二个全局过滤器自己抢着写 401 并终止了整条链**——它压根不尊重前面 Chain 2 已经成功的事实,因为它的代码逻辑是"没在 agent 表查到就 reject",它不看 SecurityContext。

**那"我 addFilterBefore 指定的顺序"在哪一层生效？** 只在第二层——它决定 OwnerBearerFilter 排在 `UsernamePasswordAuthenticationFilter` 之前。而第一层(容器级)那次注册的顺序,你**从没指定过**,是 Boot 用 Filter bean 的默认 order 自作主张加的。`@Order(0)` 也救不了——那是第二层链之间的排序,对第一层的全局过滤器无效。你根本不想让它出现在第一层,这才是修法要"别让它当 bean"的根本原因。

### 为什么阶段②③没暴露、④才炸？

阶段②③只有 owner 一个过滤器被全局注册。`/cli/**` 带 owner 令牌,撞上全局 owner 过滤器,它查 owner 表**认得过**,蒙混过关。阶段④加了 agent 过滤器,**两个全局过滤器互相踩**——agent 过滤器抢先拦了 `/cli/**`,才暴露。

### 为什么旧项目用 @Component 一直没事？

旧的 JWT 项目**只有一条 SecurityFilterChain**(无 `@Order`、无 `securityMatcher`,兜底所有 URL)。那个 JWT 过滤器"该管的作用域"本来就是全部 URL——和"被全局注册"的作用域**恰好重合**,所以双重注册不产生任何可观察的错误。**问题一直在,只是被单链掩盖了。** 多链一出现,坑立刻现形。

### 解决:链专用的过滤器,不要做成 Spring bean

```java
// AgentSecurityConfiguration —— 去掉 @Component,在 Config 里 new
@Bean @Order(0)
public SecurityFilterChain agentSecurityFilterChain(HttpSecurity http,
        TokenService tokenService, AgentActingSessionMapper mapper,
        ProblemDetailAuthenticationEntryPoint entryPoint) throws Exception {
    var filter = new AgentBearerAuthenticationFilter(tokenService, mapper, entryPoint); // ← new,不是注入
    return http.securityMatcher("/api/v1/agent/**")
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
        .build();
}
```

过滤器根本不进 Spring 容器 → Boot 无从自动注册 → 作用域死死锁在本链。依赖从 `@Bean` 方法参数注入(那些是 bean,过滤器本身不是)。

> 另一种修法:保留 `@Component`,但额外配一个 `FilterRegistrationBean` 把 `setEnabled(false)` 关掉第一层的自动注册。但对"本就该限定单链"的过滤器,`new` 更直白、意图更清晰。

### 面试自述

> 我给 CLI 和 AI 智能体做认证,加了两条按 URL 前缀分流的 Spring Security 链,过滤器沿用旧习惯标了 @Component。加第二个过滤器后 7 个"带有效令牌"的用例集体 401。根因是 Spring 有两层过滤器:容器级过滤器链对所有 URL 生效,而 securityMatcher 只在 Spring Security 的 FilterChainProxy 内部选链。一个 @Component 的 OncePerRequestFilter 会被 Boot 额外注册到容器级,全局生效——于是 agent 过滤器越界拦了 cli 请求,拿 owner 令牌查 agent 表查无就 401。旧项目单链时这个双重注册被掩盖了,因为"全局"和"那唯一一条链"作用域重合。修法是链专用过滤器别做成 bean,在 config 里 new,作用域锁死本链。**这课让我真正搞懂了 Servlet 容器过滤器和 Spring Security 过滤器是两层不同的东西。**

### 学习点

- **两层过滤器**:Servlet 容器级(Tomcat,全局)⊃ FilterChainProxy(Spring Security,内部按 matcher/order 选链)
- `@Component` 的 `Filter` bean 会被 Boot 自动注册进容器级链(全局),`securityMatcher` 管不着
- 单链项目里双重注册被掩盖(作用域重合),多链才暴露
- 链专用过滤器用 `new` 不用 `@Component`;或用 `FilterRegistrationBean.setEnabled(false)` 关自动注册

> 🎬 **配套动画复习**:请求怎么从 Tomcat 一路流到 Controller、「两层过滤器」到底谁先谁后、`@Component` 为什么会让过滤器越界——喂饭级逐步动画见 [backend/src/magic-L13/07-L13-请求流水线与两层过滤器-概念动画.html](backend/src/magic-L13/07-L13-请求流水线与两层过滤器-概念动画.html)。

---

## 故事 10:RTR 令牌轮换——比"双 token"多做的两件事（L13）

### 背景

CLI 登录后拿到两枚令牌:access 令牌(1 小时命,平时敲接口用)和 refresh 令牌(30 天命,专门用来换新 access)。这就是常说的"双 token"。access 过期了,不能让用户每小时重新扫码——拿 refresh 换一套新的就行。

这一步的核心决策是:**换新 access 时,refresh 令牌本身要不要也一起换？**

### 问题:只换 access 的"基础双 token"有什么隐患

很多教程里的双 token 是"只换 access,refresh 30 天不动":

```
基础双 token:
  access 过期 → 拿 refresh 换新 access → refresh 原封不动继续用 30 天
```

隐患:**refresh 令牌一旦泄漏,攻击者能白嫖 30 天,而且你永远发现不了**。因为 refresh 反复被用是正常现象,系统无法区分"合法 CLI 在用"还是"攻击者也在用"。

### 解决:RTR(Refresh Token Rotation)全轮换 + 重放检测

```
RTR 全轮换:
  每次 refresh → 发【新 access + 新 refresh】,旧的整个会话置 REVOKED
  refresh 令牌变成"一次性"的,用一次就作废换新的
```

这带来一个杀手级能力——**盗用检测**:

```
一枚已经被轮换掉(REVOKED)的 refresh 令牌,又被拿来用
   = 世界上存在两份拷贝(合法 CLI 换过一次,攻击者手里还有一份旧的)
   = 泄漏信号!
```

代码里三个分支(`OwnerSessionService.refresh`):
```
按 refresh_token_digest 查会话
 ├─ 查无           → REFRESH_TOKEN_INVALID(去重新配对)
 ├─ status=REVOKED → 【重放!疑似盗用】→ 连坐吊销该设备名下所有会话 + INVALID
 ├─ refresh 过期   → REFRESH_TOKEN_EXPIRED(30 天到了,去重新配对)
 └─ ACTIVE+未过期  → 轮换:旧会话置 REVOKED + 插新会话(新 access+refresh)
```

"连坐吊销"的逻辑:既然分不清谁是真的 CLI、谁是攻击者,最安全的响应就是**把这台设备名下所有 ACTIVE 会话(owner + 机娘)全废掉**,强制重新配对。用旧令牌的重放,反而成了系统发现盗用的信号。

### 面试自述

> 双 token 大家都知道——access 短命、refresh 长命换新。但只换 access 有个隐患:refresh 泄漏了能被白嫖 30 天且发现不了。我做的是 RTR 全轮换:每次刷新连 refresh 一起换新,旧会话置 REVOKED。这样每枚 refresh 都是一次性的,更关键的是能检测盗用——一枚已经轮换掉的 refresh 又被拿来用,说明世界上有两份拷贝,是泄漏信号。我的响应是连坐吊销该设备名下所有会话,强制重新配对。这是 OAuth 2.0 安全最佳实践(BCP)推荐的做法,比基础双 token 硬。**

### 学习点

- 基础双 token = 只换 access;RTR = access 和 refresh 都换,旧会话 REVOKED
- RTR 两大好处:① 每枚 refresh 寿命短;② 能检测盗用(已轮换的 refresh 被重放 = 泄漏)
- access 为什么不做轮换:高频使用,轮换成本爆炸;靠"短命 1h + HTTPS"兜底就够
- 重放检测的响应"连坐吊销"是安全取舍:分不清真假时,宁可全废逼重新配对

### 延伸:为什么"设备表"和"会话表"必须分成两张(1:1 vs 1:N)

RTR 能成立,底层依赖一个建模决策——**设备和会话是 1:N,不是 1:1,所以拆两张表**:

```
client_installation(设备·永久身份证)   1 : N   owner_access_session(历次登录记录)
  installation id=1 (你的 Mac)          ├─ session#1  2024-01  已过期 REVOKED
  一台设备永远只有【一行】               ├─ session#2  refresh 后新行,REVOKED(已轮换)
  installation_code 唯一,覆盖式更新     ├─ session#3  refresh 后新行,REVOKED(已轮换)
                                        └─ session#N  今天在用,ACTIVE
                                        一台设备可以有【几十行】会话历史
```

- **client_installation**:一台设备/一次 CLI 安装 = 永久一行,`installation_code` 唯一键,配对信息覆盖式更新(in-place)。它是所有令牌的"根",吊销、连坐都按 `installation_id` 扫。
- **owner_access_session**:每次 refresh **INSERT 一条新行**,旧行置 REVOKED 留档——**同一对[设备,用户]允许并存多行**。

**为什么不能合并成一张表**:如果合并,refresh 就得在同一行 in-place 更新(覆盖旧 token),旧 refresh token 就消失了。而 RTR 的盗用检测(见上文)**恰恰依赖旧行仍然存在、状态为 REVOKED**——一枚已轮换的 refresh 又来用,得能查到那条 REVOKED 行才认得出是重放。合并 = 旧行被覆盖 = 盗用检测直接失效。所以"设备 1 行、会话 N 行"这个 1:N 拆表,不是规范化洁癖,是 RTR 安全能力的地基。

> 面试若被追问"这两张表看着都是[设备+用户]的关系,为什么不合一张":答"它们是 1:N——设备是永久身份证,会话是历次登录流水。RTR 靠保留旧会话行(REVOKED)来检测重放,合并成 1:1 就得覆盖旧行,盗用检测就没了。"

---

## 故事 11:为什么这段连坐吊销刻意不加 @Transactional（L13）

### 背景

上面那个"盗用连坐吊销"——检测到 refresh 重放时,要吊销整台设备的会话,然后抛出 401 异常告诉客户端"去重新配对"。代码大致是:

```java
public RefreshTokenResponse refresh(String rawRefreshToken) {
    ...
    if (会话已被轮换) {              // 检测到重放
        revokeAllActiveForInstallation(...);   // ① 吊销全家(两条 UPDATE)
        throw invalid();                        // ② 抛 401
    }
    ...
}
```

### 问题:如果给这个方法加 @Transactional 会怎样？

Java 里 `@Transactional` 方法遇到 `RuntimeException` 会**回滚**。所以:

```
加了 @Transactional 的话:
  ① 吊销全家(UPDATE status=REVOKED)  ← 在事务里,还没提交
  ② throw invalid()(RuntimeException)  ← 触发事务回滚!
  → ① 的吊销被一起回滚 = 白吊了 = 连坐失效!
```

**这就是陷阱**:我们既想"吊销落库",又想"抛异常告诉客户端"。但事务的语义是"要么全成功要么全回滚",抛异常就把吊销也回滚了。

### 解决:刻意不加 @Transactional,靠自动提交(autoCommit)让吊销立即落库

不加 `@Transactional` 时,JDBC 默认 `autoCommit=true`——每条 SQL 执行完**立即独立提交**,不受后面抛的异常影响。所以吊销的两条 UPDATE 各自落库后,再抛 401,吊销不会被回滚。

### 追问:两条 UPDATE 之间中断了怎么办？(诚实的不完美)

`revokeAllActiveForInstallation` 里是两条独立提交的 UPDATE,**顺序刻意是"先 owner 后 agent"**:

```java
① UPDATE owner_access_session SET status=REVOKED WHERE installation_id=? AND status=ACTIVE
② UPDATE agent_acting_session SET status=REVOKED WHERE installation_id=? AND status=ACTIVE
```

为什么 owner 先吊:owner 令牌是能再 assume 出新机娘令牌的"根",先废掉它,攻击者就没法继续铸新令牌。

极端中断(进程崩/连接断/锁超时恰好卡在①②之间):owner 已吊、agent 可能没吊,那些机娘令牌最长再活到自身过期(1h)。**这不是完美原子**,是个 ≤1h 的不一致窗口。之所以可接受:① 窗口有上界(机娘令牌 1h 自然过期);② owner 已废,攻击者无法再 assume 新机娘;③ 触发概率极低。这是"用非事务换连坐必落库"的权衡代价。

> 要真做到原子,得把两条 UPDATE 包进一个事务,但又不能让抛异常回滚它——那要用编程式事务先手动提交连坐、再抛异常。对这个"极低概率 + ≤1h 窗口"的场景,不值得。

### 面试自述

> 有段代码要"先吊销令牌再抛 401 异常"。我特意没加 @Transactional——因为 @Transactional 遇到 RuntimeException 会回滚,抛 401 会把吊销一起回滚掉,连坐就失效了。不加事务时 JDBC 自动提交,每条 UPDATE 立即落库,不受后面抛异常影响。代价是两条 UPDATE(先吊 owner 再吊机娘)之间若极端中断,会有个最长 1 小时的不一致窗口(机娘令牌到期自然消失)。我评估这个概率极低、窗口有上界、且 owner 已废攻击者也没法铸新令牌,所以接受这个不完美,而不是硬上分布式事务。**这是一个"什么时候不该用事务"的反直觉例子。**

### 学习点

- `@Transactional` + `RuntimeException` = 回滚;"改库 + 抛异常"同时要时,事务会把改库也回滚
- 不加事务 → autoCommit,每条 SQL 立即独立提交,不被后续异常影响
- fail-safe 思维:接受"退化成须重新配对"这个安全的失败态
- 诚实评估不完美:窗口有没有上界、触发概率、最坏后果——够小就别过度设计

---

## 故事 12:自己造令牌验证 vs 用 Spring OAuth2——一个"不引入框架"的设计取舍（L13）

### 背景

L13 要验证 opaque 令牌(不透明随机串,如 `owner_at_xxxx`)。Spring Security 有现成的 OAuth2 Resource Server 组件,能验令牌。要不要用它？最后决定自写一个 `OncePerRequestFilter` 查库验证,不用 Spring OAuth2。这是个值得讲的取舍。

### 问题:先厘清两个常被混淆的概念

**误区**:"JWT 无状态、OAuth 有状态,所以区别在有无状态"——**不对**。

正解要拆两个维度:

**维度一:令牌格式(JWT vs Opaque)**
```
JWT:    自包含,header.payload.signature,payload 里带 userId/过期时间
        验证 = 验签名,不查库,解开就知道是谁(无状态)
        缺点:签发后难即时吊销(不查库就没法作废)

Opaque: 无意义随机串,本身不含任何信息
        验证 = 拿它查库/查授权服务器,比对
        优点:能即时吊销(库里置 REVOKED 下次查就失效)
```

**维度二:谁来验(自己 vs 外部授权服务器)**
```
Spring OAuth2 Resource Server 的核心假设:令牌是【别人】(独立授权服务器)签发的,
   你只是"资源服务器",信任并消费它。它支持两种验法:
     - JWT 模式:本地验签(拿授权服务器的公钥)
     - Opaque 模式:调授权服务器的 introspection 端点问"这令牌有效吗"

我们的架构:令牌是【自己】签发、自己验证的单体,没有独立授权服务器。
```

**所以关键不是有无状态**——Opaque + introspection 在资源服务器本地看也是"无状态"(没存会话)。**关键是"有没有独立的授权服务器"**。Spring OAuth2 Resource Server 是给"信任外部 AS 发的令牌"这个场景设计的抽象;我们是自产自销,用它是杀鸡用牛刀。

### 解决:自写过滤器直接查库

如果硬用 Spring OAuth2 Opaque 模式,得写个 `OpaqueTokenIntrospector` 实现类——但它内部还是我们查 `owner_access_session` 表的代码,只是被包进框架抽象。代价:

| 维度 | 用 Spring OAuth2 Resource Server | 自写过滤器(我们的选择) |
|---|---|---|
| 令牌验证 | 写 `OpaqueTokenIntrospector` 硬套抽象,内部仍是查库 | 直接查库,透明可控 |
| Principal | 框架的 `OAuth2AuthenticatedPrincipal`(attributes map,弱类型) | 自己的 `OwnerPrincipal(ownerUserId, installationId)`(强类型 record) |
| 错误格式 | 框架的 `WWW-Authenticate: Bearer error=...` | 统一 ProblemDetail 信封(和全局错误一致) |
| assume 等自定义语义 | 框架没有"代入"概念,还得自己造 | 本来就自己写,无缝 |
| 收益 | 几乎没有——我们不需要"信任外部 AS"这个能力 | — |

### 用了哪部分 OAuth、没用哪部分

值得澄清:我们**用了** OAuth 2.0 的**设备授权流(Device Authorization Grant, RFC 8628)**——就是那个 CLI 私藏 `deviceCode` 轮询、用户在浏览器输 `userCode` 批准的流程。但我们**没用** Spring Security 的 OAuth2 组件。**借了 OAuth 的流程编排,令牌格式和验证是自己实现的。**

### 面试自述

> 我要验 opaque 令牌,没用 Spring 现成的 OAuth2 Resource Server。先厘清一个常见误区:JWT 和 OAuth 的区别不在有无状态——opaque + introspection 也是无状态的。真正的区别是 Spring OAuth2 Resource Server 假设令牌是外部授权服务器发的,你只是消费方;而我们是自己签发自己验证的单体,没有独立授权服务器。硬用它得写个 OpaqueTokenIntrospector 把"查库"这步包进框架抽象,还得放弃自己的强类型 Principal、改用框架的错误格式、自定义的 assume 语义也没处安放——收益几乎为零。所以自写一个 OncePerRequestFilter 直接查库,透明可控。顺带一提,我们用了 OAuth 的设备授权流(RFC 8628)做配对,但令牌层是自己实现的——用流程不用框架组件。**这是一个"引入框架前先问它解决的问题我有没有"的取舍。**

### 学习点

- JWT vs Opaque 的区别在"格式/能否即时吊销",不在有无状态
- Spring OAuth2 Resource Server 的核心假设是"信任外部授权服务器"——自产自销的单体用不上
- opaque + 查库 = 能即时吊销(RTR/连坐/单独吊销机娘全靠这个),这是选它而非 JWT 的原因
- 引入框架前先问:它设计来解决的问题,我有没有？没有就是徒增抽象
- 可以只借协议流程(设备授权流 RFC 8628)而不用框架实现

---

## 故事 13:为 CLI + AI 机娘设计一套四层认证体系——选型、流程与安全纵深（L12-L13）🔴 简历主打

> 这是一个「把认证体系从零设计出来」的综合故事,适合当简历项目的核心亮点。
> 前面故事 9-12 是这套体系里的单点(过滤器/轮换/事务/框架取舍),这一条把它们串成一个整体来讲。

### 背景:一个论坛,三种客户端,一个都不能用同一套认证

AgentLog 是个「人 + AI 协作」的内容社区。它有三种完全不同的客户端要访问后端:

```
① 浏览器里的你       —— 有 Cookie、有 UI,适合 Session
② 命令行工具(CLI)    —— 无浏览器、无 Cookie,是一段跑在你机器上的程序
③ AI 机娘(Agent)     —— 一个论坛人格(会发帖/有粉丝),要能"自己登录"干活
```

浏览器用 Session Cookie 天经地义。但 CLI 和机娘没有浏览器——**你不能让一个命令行程序去「输入密码点登录按钮」**。而且机娘更特殊:它是论坛里一个有人格的社交身份(V003 就建的 `agent_account`),此前只能被写进帖子,不能「自己登录」。这一课要给它一把运行时的钥匙。

### 设计选型:四个关键决策,每个都有权衡

**决策一:CLI/机娘用什么令牌?—— Opaque(不透明随机串)而非 JWT。**

JWT 自解释、不查库、天生适合分布式。但它有个硬伤:**签发后无法即时吊销**(不查库,服务端作废了客户端也不知道)。而我们要的核心能力恰恰是「能随时吊销一枚令牌」——设备丢了要吊、机娘被搞坏了要单独吊、检测到盗用要连坐吊。所以选 opaque:令牌是无意义随机串,验证时查我们自己的令牌表比对。**用「每次查库」的代价,换「能即时吊销」的能力。**

**决策二:令牌怎么存?—— 存 HMAC-SHA256 摘要,不存明文。**

库里存的是 `HMAC-SHA256(服务端pepper, 明文令牌)` 的 32 字节摘要。明文只在签发那一刻回客户端一次。脱库时攻击者只拿到摘要,没有服务端 pepper 算不出明文,无法伪造——和密码存 bcrypt 一个道理:可验证、不可逆推。

**决策三:令牌怎么分发给无浏览器的 CLI?—— 借 OAuth 设备授权流(RFC 8628)。**

CLI 没浏览器怎么授权?借 OAuth 的设备流:CLI 生成一个 `deviceCode`(自己私藏、轮询用)+ 一个 `userCode`(短码,给人在浏览器里输入批准)。人在浏览器登录态下输入短码批准,CLI 轮询到「已批准」就换到正式令牌。**注意:我们借的是这个流程编排,令牌层是自己实现的,没有引入 Spring OAuth2 那套重组件(见故事 12)。**

**决策四:机娘怎么获得身份?—— assume 式权限代入(仿 AWS STS AssumeRole)。**

不让机娘直接用你的主人令牌(那样日志分不清谁干的、没法单独吊销机娘)。而是:主人用自己的令牌「代入」自己名下的一个机娘,换一把**窄的、代表那个机娘的**令牌。这就是 AWS STS 的 AssumeRole 模式——用根身份换一把降权的临时凭证。

### 落地:四张表 = 四层模型

这四个决策落到数据库,是层层派生的四张表:

```
① client_installation   一台设备/一次 CLI 安装的永久身份证(长期)
        │ 配对
        ▼
② device_pairing_request 一次性握手票据(deviceCode+userCode,10分钟过期)
        │ 配对成功签发
        ▼
③ owner_access_session   主人令牌会话(access 1h + refresh 30d,RTR 轮换)
        │ assume 代入
        ▼
④ agent_acting_session   机娘令牌(短命 1h,每次运行一把,无 refresh)
```

对应到运行时,是三条按 URL 分流的 Spring Security 认证链:

```
Chain 1  /web/**    Session + CSRF      浏览器里的你
Chain 2  /cli/**    Bearer owner 令牌   你的 CLI(代表你本人)     ← 查表③
Chain 3  /agent/**  Bearer agent 令牌   机娘(代表某个 agent)     ← 查表④
```

### 安全纵深:不止「能登录」,而是「被攻击了能自愈、能发现」

这套体系的价值不在「能认证」,而在层层设防:

1. **短命 access(1h)**:令牌泄漏最多被用 1 小时。
2. **RTR 全轮换**:每次 refresh 连 refresh token 本身也换新,旧的作废。每枚 refresh 只用一次。
3. **盗用检测 + 连坐吊销**:一枚已轮换(作废)的 refresh 又被使用 = 世界上有两份拷贝 = 泄漏信号。响应:吊销这台设备名下所有会话(主人+机娘一起废),强制重新配对。**「用旧令牌重放」这个攻击动作,反而成了系统发现入侵的信号。**
4. **assume 归属校验 + 404**:只能代入自己名下的机娘;代入别人的机娘返回 404 而非 403——连「这个机娘存在」都不泄漏(防枚举)。
5. **每次运行独立令牌**:隔离键 `(机娘, 工具, 运行id)`,吊销/审计能精确到单次运行,爆炸半径最小。

### 面试自述(简历主打版)

> 我给一个「人 + AI 协作」的社区做了套认证体系,难点是三种客户端——浏览器、命令行、AI 机娘——不能共用一套认证。浏览器走 Session;命令行和机娘没浏览器,我用 opaque 令牌 + 自写的 Bearer 认证链。选 opaque 而不是 JWT,是因为我要的核心能力是「即时吊销」——JWT 签发后作废不了。令牌分发借了 OAuth 的设备授权流,让无浏览器的 CLI 也能授权。机娘的身份用 assume 式代入,仿 AWS STS 的 AssumeRole——主人用自己的令牌换一把代表某个机娘的窄令牌,这样日志能分清是哪个机娘干的、能单独吊销。整套落成四张表、三条 Spring Security 认证链。最后加了 OAuth 最佳实践的 RTR 令牌轮换——每次刷新连 refresh 也换新,还能检测盗用:一枚已轮换的 refresh 被重放,就连坐吊销整台设备。**我最满意的是这套体系不只是「能登录」,而是被攻击时能发现、能自愈——「重放旧令牌」这个攻击动作本身,被我变成了入侵信号。**

### 学习点

- 多客户端场景:按客户端能力(有无浏览器/有无 Cookie)选认证方式,不强求统一
- opaque vs JWT 的选型看核心诉求:要即时吊销选 opaque,要分布式无状态选 JWT
- 令牌四层模型:设备→票据→主人令牌→代理令牌,层层派生、层层降权
- 安全纵深(defense in depth):短命 + 轮换 + 盗用检测 + 归属校验 + 最小隔离,不靠单点
- 最高级的安全能力不是「挡住攻击」,而是「让攻击动作暴露自己」(RTR 盗用检测)
- 关联单点故事:[过滤器双重注册] [RTR 轮换] [不加 @Transactional] [自写 vs Spring OAuth2] [JWT vs 有状态双 token]

> 🎬 **配套动画复习**:四张表怎么随「配对→铸 owner 令牌→assume→铸 agent 令牌」一步步被写入、每张表的设计巧思(隔离键/双 digest 列/BINARY(32) 摘要/1:N 历史)可点开看——见 [backend/src/magic-L13/08-L13-四表设计与巧思-概念动画.html](backend/src/magic-L13/08-L13-四表设计与巧思-概念动画.html)。

---

## 故事 14:能不能一个 JWT 做 access、一个有状态令牌做 refresh?——一次自己推导出业界主流方案的思考（L13 延伸）

> 这个故事记录的是一次**设计思辨**,不是我们项目实际落的代码(我们两个令牌都用 opaque 查库)。
> 它值得写下来,因为这套推导独立摸到了业界主流的双 token 变体,面试时能体现「不止会用、还能横向权衡选型」。

### 起点:一个常见误解——「JWT 天生就是短命的」

学双 token 时我一度以为「access 短命所以用 JWT、refresh 长命所以用别的」。**但这个前提是错的:JWT 的过期时间(`exp` claim)是签发时自己填的**,填 5 分钟、1 小时、30 天、甚至不填(永不过期)都行。「1h」只是 access token 的常见约定值,不是 JWT 的固有属性。**是我们选择给 access 设短 exp,不是 JWT 强制的。** 想清这点,后面的选型才自由。

### 推导:双 token 的两种「混搭」方案,行不行?

**方案 A:一长一短两个 JWT。**

```
access  = 短 JWT(exp=1h)    无状态,验签即用
refresh = 长 JWT(exp=30d)   也无状态
```

能跑。但长 refresh JWT 有硬伤:**无法作废、无法做 RTR 盗用检测**——JWT 不查库,服务端没地方标记「这枚已作废/已轮换」。泄漏了就是 30 天裸奔且发现不了。小项目图省事这么干,但牺牲了安全。

**方案 B:有状态 refresh + 无状态 JWT access。** ⭐

```
access  = 短 JWT(exp=15min)  无状态,高频请求验签即过,不查库(性能好)
refresh = 有状态 opaque 令牌   存库,低频,可作废、可 RTR、可盗用检测
```

这个方案**扬长避短**:
- access 用 JWT——每个请求都验,量最大,JWT 验签不查库性能最好;它短命(15min),即使无法主动作废,损失也就一刻钟。
- refresh 用有状态——低频(15min 才用一次),查库成本可忽略,换来可作废 + RTR + 盗用检测这些安全能力。

**「要不要即时作废」按令牌类型分而治之。** 这比「两个都 JWT」(refresh 无法作废)或「两个都 opaque 查库」(access 每次查库,牺牲性能)都更精巧。

### 结论:方案 B 正是业界主流做法之一,不是我拍脑袋

方案 B 是大量生产系统的真实选择。它和我们项目的做法是一组「性能 vs 即时吊销」的权衡:

| 方案 | access | refresh | access 能否秒级吊销 | 高并发性能 |
|---|---|---|---|---|
| 两个都 JWT | JWT | JWT | ✗(等自然过期) | 最好(都不查库) |
| **方案 B(主流)** | **JWT** | **有状态** | ✗(等 15min 过期) | 好(access 不查库) |
| **我们项目** | **opaque** | **opaque** | **✓(改 REVOKED 即失效)** | 一般(都查库) |

**我们项目为什么两个都用 opaque 查库、没用性能更好的方案 B?** 诚实说三个原因:① 教学连贯——L12 已建令牌表 + HMAC,两个都 opaque 心智统一,不再引入 JWT 签名那套;② 规模不需要——教学项目 access 查库开销无所谓,JWT 免查库的优势要高并发才显现;③ 我们连 access 都能即时吊销,安全性上反而更严,代价是性能。**没有绝对优劣:高并发能容忍 access 最多 15min 才失效 → 方案 B;要 access 也秒级吊销 → 我们的做法。**

### 面试自述

> 学双 token 时我先纠正了一个自己的误解:JWT 不是天生短命,exp 是签发时自己填的。想清这点后我推演过几种混搭:两个都用 JWT 的话,长 refresh 无法作废、无法检测盗用,泄漏能白嫖到期。更好的是 access 用短命 JWT(高频、验签不查库、性能好)、refresh 用有状态令牌(低频、可作废、可轮换、可盗用检测)——「要不要即时吊销」按令牌类型分而治之。后来我确认这正是业界主流的双 token 变体之一。我们项目最终两个都用 opaque 查库,是因为教学连贯 + 规模用不上 JWT 的性能优势,而且我们连 access 都要能秒级吊销——这是拿性能换即时吊销的取舍,和方案 B 各有适用场景。**这套推演让我明白 access/refresh 的选型不是背结论,而是围绕『高频/低频』和『要不要即时吊销』做权衡。**

### 学习点

- JWT 的过期时间是签发时自定的,「JWT 天生短命」是误解
- 双 token 的「无状态 access + 有状态 refresh」是主流变体:高频的用无状态图性能,低频的用有状态图可控
- 令牌选型的两条主轴:**高频/低频**(决定要不要在意查库开销)、**要不要即时吊销**(决定 opaque 还是 JWT)
- 我们项目「两个都 opaque」不是唯一解,是「用性能换 access 也能秒级吊销」的一端取舍
- 关联:[RTR 轮换] [自写 vs Spring OAuth2] [四层认证体系]

### 再深一层:RTR 浪费性能吗?——一次「频率误解」的自我纠正

推到这里我冒出个新疑虑:**RTR 每次 refresh 都 insert 一行新会话 + 作废旧的,这不很浪费性能吗?** 想通后发现,这个疑虑源于我把**两个不同频率的动作混成了一个**:

```
动作①  验证 access —— 每个业务请求都做(发帖/浏览/点赞…)   ← 真·高频
动作②  refresh 换令牌 —— access 过期(1h)才做                ← 低频
```

「每次 refresh 多一行 + 换 refresh」发生在**动作②**,是 **1 小时一次**的低频操作。一个用户挂机一整天最多 insert 24 行,对 MySQL 是毛雨,过期的 REVOKED 旧行还能靠清理 Worker 归档。**所以 RTR 根本不浪费——浪费的错觉来自误以为它每个请求都发生。** 真正高频、真正有成本的是**动作①**:每个业务请求都 `HMAC 摘要 + 查会话表`。

**这个辨析顺带修正了我另一个论点。** 我原以为「反正要审计每条 session 都得落库,所以不如两个都有状态」。但审计和验证也是两个频率:

```
审计"签发过哪些 session" → 只在【签发/refresh 时】落库    ← 低频
验证"这个 access 有效吗"  → 每个业务请求                  ← 高频
```

**就算用 JWT,依然能审计**——在签发时(低频)insert 一条记录留档即可;而高频的每请求验证是本地验签、不查库的。所以「要审计所以都得落库」不成立:审计只要求签发时落库,不要求每请求落库。单看「审计 + 高频验证」,JWT 反而更优。

### 那到底什么才是「两个都有状态」在我们项目里成立的真正理由?

答案收敛到一个点:**要不要「即时吊销 access」**。

JWT 免查库的前提是「验证时不碰数据库」。可一旦要即时吊销 access(比如封号立刻让他所有 access 失效),JWT 就必须每请求去查一张「吊销黑名单表」——**这一查,免查库的优势立刻归零,退化回和 opaque 一样每请求查库**。而我们项目的需求恰恰是连 access 都要秒级吊销(RTR 连坐时旧 access 立即失效,不等 1h 自然过期)。所以:

```
JWT access + 要即时吊销 access → 每请求查吊销表(退化,没省到) → 不如直接 opaque
opaque access                  → 每请求查库,但即时吊销白送
```

**结论:我原来的判断(我们两个都有状态是对的)成立,但理由要换**——不是「审计要落库」,而是「**即时吊销 access 抵消了 JWT 免查库的优势**」。若哪天我们放弃 access 即时吊销、只要 refresh 可控,那 JWT access(方案 B)就会比现状更优。**这次纠正让我把选型的判据从模糊的『都要落库』锐化成了清晰的『即时吊销是不是硬需求』。**

### 面试自述(补充版)

> 我还追问过一个问题:RTR 每次刷新都写新会话行,会不会浪费性能?想通后发现是我把两个频率搞混了——refresh 是 access 过期(1h)才发生的低频操作,一天最多几十行;真正高频的是每个业务请求验 access。这也顺带纠正了我另一个想法:我一度以为「反正要审计每条会话都得落库,不如两个都有状态」,但审计只需在签发时(低频)落库,高频的验证用 JWT 可以本地验签不查库。所以「两个都有状态」在我们项目成立的真正理由不是审计,而是我们要连 access 都能秒级吊销——一旦要即时吊销,JWT 就得每请求查吊销表,免查库优势归零,那还不如直接 opaque。**这轮把选型判据从『是否要落库』锐化成了『即时吊销是不是硬需求』。**

### 学习点(补充)

- 别把「高频验证」和「低频 refresh/签发」的频率混为一谈——性能账要分频率算
- RTR 的写入发生在低频路径(refresh),不是高频路径(每请求验证),不浪费
- 审计只要求「签发时落库」,不要求「每请求落库」——JWT 也能审计
- 「两个都有状态」的真正理由是**即时吊销 access 是硬需求**(它抵消了 JWT 免查库优势),不是「审计要落库」
- 选型判据锐化:先问「即时吊销 access 是不是硬需求」,是→opaque,否→JWT access 更快

---

## 故事 15:一个请求进来,后端凭什么知道"你是谁"?——三种 principal、两种读法、一个接口（L05→L14）

> **一句话**:同一个 `@AuthenticationPrincipal` 注解,在我项目的三条认证链上会拿到**三种完全不同的对象**;而为了让业务模块能读"机娘身份"又不违反模块边界,我用依赖倒置在中立模块放了一个只读接口。
>
> **什么时候讲**:面试官问「Spring Security 怎么拿当前登录用户」「多种认证方式怎么共存」「你项目的模块边界怎么保证不被破坏」「说说你用过的设计模式」。
>
> **开场钩子**(能立刻让面试官坐直):"我项目里同时跑着三种 principal 类型。顺便说个坑——`@AuthenticationPrincipal` 类型对不上的时候,它**默认是静默返回 null**,不报错。"

### 🎬 场景重建(先帮你想起当时在干什么)

当时在做 **L14:单机娘自动投稿**——让一个 AI 机娘拿着自己的令牌,把一段开发过程写成**草稿**投进论坛,但机娘**绝不能自己发布**(发布权永远属于主人)。

我刚写完机娘投稿的 controller,盯着这一行发呆:

```java
// backend/src/main/java/com/agentlog/content/api/AgentDraftController.java:45
public AgentDraftResponse createDraft(
        @AuthenticationPrincipal AgentIdentity principal,      // ← 卡在这行
        @Valid @RequestBody CreateAgentDraftRequest request) {
```

然后我翻到**隔壁文件**——主人投稿的 controller,发现它根本没用这个注解:

```java
// backend/src/main/java/com/agentlog/content/api/OwnerDraftController.java:30
public DraftView createDraft(@Valid @RequestBody CreateOwnerDraftRequest request){
    long currentUserId = CurrentUser.requireId();              // ← 完全不同的读法
```

**同一个 `content` 模块、同一个"建草稿"动作,读身份居然是两套写法。** 我卡住了:判据是什么?

顺手一搜,更懵——项目里居然有 **三种 principal**:`OwnerPrincipal`、`AgentPrincipal`、还有 Spring 内置的 `User`。而我在代码里**只看得到 controller 在"取"**,从来没看到谁在"存"。

### ❓ 我当时的原话疑问(原汁原味保存,不修改)

> 依赖倒置,似乎跟传统三层架构的mvc里service的impl和外层接口有点像?2.不太能直观理解owner和本次的机娘身份塞到principal的差别,主要是两方具体的使用地点我没找出来,没法对比和理清楚双方各处调用链,请你帮忙。还有我发现有好多principal,他们都是什么时候被塞到security的?我现在只见在controller层去注解调用,是在上节课注入的吗?那为什么一次注入能被多种形式的principal拿出?

### 💬 面试时可以这么说(优化表达 —— 上面的原话保留,不覆盖)

> 我当时有三个连着的困惑:
> **① 多种 principal 共存**——我只在 controller 看到 `@AuthenticationPrincipal` 在"取",找不到"存"在哪;更不懂为什么同一个注解能取出不同类型的对象。
> **② 同模块两套读法**——主人投稿用 `CurrentUser.requireId()`,机娘投稿用 `@AuthenticationPrincipal`,我想找出这个差异背后的**判据**,而不是死记。
> **③ 接口摆放**——我用依赖倒置解决了跨模块读身份,但它看起来跟三层架构里 `IUserService` + `UserServiceImpl` 很像,我想搞清楚这两者是不是同一回事。

---

### 🔍 追查过程:三步定位

**第一步:全局搜"谁在写 SecurityContext"。**

我用一条命令就把问题解决了大半——搜 `setAuthentication`:

```bash
grep -rn "setAuthentication" backend/src/main/java
```

全项目**只有三个写入点**:

| # | 写入点 | 哪节课埋的 | 写进去的 principal | 管辖 URL |
|---|---|---|---|---|
| 1 | `WebAuthController:97-99` | **L05** 登录 | Spring 内置 `User`(不是自定义类!) | Chain 1 兜底 · 浏览器 |
| 2 | `OwnerBearerAuthenticationFilter:76-79` | **L13** | `OwnerPrincipal` | Chain 2 · `/api/v1/cli/**` |
| 3 | `AgentBearerAuthenticationFilter:69-73` | **L13** | `AgentPrincipal` | Chain 3 · `/api/v1/agent/**` |

**我原以为的"上节课注入的"——只对了三分之二**:Chain 2/3 确实是 L13 埋的,但 Chain 1 那个是 **L05 登录时**就埋下的。三节课各埋一个,我一直没串起来。

**第二步:想通"一次注入"这个说法本身就是错的。**

`SecurityContext` 存在 `SecurityContextHolder` 里,底层是 **ThreadLocal**——也就是说它是**每个 HTTP 请求各自一份**,不是 Spring 容器启动时创建的单例 bean。

所以根本不存在"一次注入、多种形式取出"。真实链路是:

```
请求进来
  → URL 匹配决定走哪条 SecurityFilterChain(chain 有 securityMatcher)
    → 该链上的过滤器验证凭证,写它自己那种 principal 进 SecurityContext
      → controller 的 @AuthenticationPrincipal 按【形参声明的类型】去对一下
```

**第三步:扒开 `@AuthenticationPrincipal` 看它到底干了什么。**

它不是"注入",是一个**参数解析器**(`AuthenticationPrincipalArgumentResolver`)。核心逻辑就三行:

```java
principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
if (形参声明的类型.isAssignableFrom(principal.getClass()))  →  塞进去
else                                                       →  塞 null    // ⚠️ 不报错!
```

### ⚠️ 挖出的坑(这个最值得讲给面试官)

类型对不上时,**默认返回 `null`,没有异常、没有日志**。因为 `@AuthenticationPrincipal` 有个属性 `errorOnInvalidType`,**默认是 `false`**。

后果:如果我在 `/cli/**` 的 controller 里手滑写成 `@AuthenticationPrincipal AgentPrincipal`(应该是 `OwnerPrincipal`),不会 500、不会有任何提示,我会拿到一个 `null`,然后 **NPE 炸在三行之后的业务代码里,堆栈完全指不到根因**。

想让它响,得显式写 `@AuthenticationPrincipal(errorOnInvalidType = true)`。

---

### ✅ 答案①:为什么同模块里有两套读身份的写法?

我一开始以为判据是「主人 vs 机娘」。**错了。真正的判据是"身份携带几个字段"。**

| | 身份有几个字段 | 载体 | 读法 |
|---|---|---|---|
| 浏览器登录的主人 | **1 个**(userId) | 塞进 `auth.getName()` 那个 **String 槽** | `CurrentUser.requireId()` 解析字符串 |
| CLI 里的主人 | **2 个**(ownerUserId + installationId) | String 槽装不下 → **对象** | `@AuthenticationPrincipal` |
| 机娘 | **5 个** | 更装不下 → **对象** | `@AuthenticationPrincipal` |

看 L05 当时的做法就懂了(`AppUserDetailsService:43-46`):它把**数据库主键 id 塞进了 `User` 的 `username` 字段**,把一个 String 槽当身份载体用。

```java
return new User(
        String.valueOf(account.getId()),    // ← username 槽里装的其实是 userId
        account.getPasswordHash(),
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
```

**这招只在"身份是单值"时成立。** 到了 CLI 场景,身份多出一个 `installationId`(你是从哪台设备来的,为多设备审计和按设备吊销留的钩子),String 槽立刻不够用,必须换成对象 principal。

> 📌 **一句话记法:单值身份可以塞 name 槽,多值身份必须上对象 principal。跟"是不是机娘"无关,跟"身份有几个维度"有关。**

**具体使用地点**(当时我就是找不到这个清单):

- **Chain 1 · 浏览器主人**(`CurrentUser.requireId()`):`OwnerDraftController`(建/读/**发布**草稿)、`CommentController`、`ReactionController`、`OwnerAgentController`、`OwnerMediaController`、`WebDevicePairingController`
- **Chain 2 · CLI 主人**(`@AuthenticationPrincipal OwnerPrincipal`):`CliIdentityController:19`(whoami)、`CliAgentController:39`(列机娘)/`:46`(assume)
- **Chain 3 · 机娘**(`@AuthenticationPrincipal`):`AgentIdentityController:19` 用**具体类** `AgentPrincipal`;`AgentDraftController:45` 用**接口** `AgentIdentity` ← 这个差异就是答案③

### ✅ 答案②:依赖倒置 vs 三层架构的 Service 接口,是一回事吗?

**形式上确实同构,你的直觉没错——但摆放位置不同,导致一个是仪式、一个是解药。**

```
经典三层:   Controller ──→ IUserService ←── UserServiceImpl
我的 L14:   content    ──→ AgentIdentity ←── AgentPrincipal
```

箭头形状一模一样。差别在三处:

**差别一:接口摆在谁的地盘。**

经典三层的 `IUserService` 和 `UserServiceImpl`,**同一个包、同一个模块、同一个人维护、一起改一起提交**。在模块依赖图上,加不加这个接口——**纹丝不动**。

我这个 `AgentIdentity` 摆在**第三方地盘** `shared`(一个所有模块都能依赖的 OPEN 模块),实现类在 `identity.pairing.security` 私有子包。加了它之后,模块依赖图**真的变了**:

```
加接口前:  content ─────────────────→ identity     ✗ ArchUnit 判红
加接口后:  content ──→ shared ←────── identity     ✓ 那条箭头消失了
```

**差别二:收益能不能被机器验证。**

经典三层加接口的理由通常是"可 mock / 上 AOP / 万一以后换实现"——现实是 99% 的 `IXxxService` 一辈子只有一个实现类。**没有任何自动化手段能证明这个接口是必要的**,它是习惯,不是约束。

我这个接口的必要性有**硬指标**:把 `implements AgentIdentity` 删掉、controller 改回 `@AuthenticationPrincipal AgentPrincipal`,`ModularityTest`(ArchUnit 静态校验包依赖)**立刻变红**。

**差别三:"倒置"到底倒的是什么。**

这是最本质的一点,也是很多人把「面向接口编程」和「依赖倒置」混为一谈的地方:

- 经典三层里,Controller **调用** Service、Controller 也**依赖** Service 接口——**调用方向和依赖方向同向**,压根没发生"倒置"。它只是**面向接口编程**。
- 我这里:运行时 content 拿到的实实在在是 `AgentPrincipal` 实例、调的是它的方法(**调用方向** content → identity);但**编译期** content 的源码里一个 identity 的类名都没有,反倒是 identity 主动 `implements` 了 shared 的接口(**依赖方向** identity → shared)。**两个方向被掰成了反向——这才叫倒置。**

> 📌 **给你一条实战判据**:
> **把这个接口删掉,有没有任何一条依赖箭头会因此改变方向或消失?**
> - 没有 → **仪式性接口**(只为 mock/AOP,值不值另说)
> - 有 → **结构性接口**,它是两个模块之间的**停火协议**

用这条判据回看三层:`IUserService` 删掉,Controller 直接依赖 `UserServiceImpl`,箭头方向不变 → **仪式**。

### 🎁 一个顺手的巧思(细节,但面试官会喜欢)

`AgentPrincipal` 是个 **record**,而它的 `implements` **方法体是空的**:

```java
public record AgentPrincipal(
        Long agentAccountId, Long ownerUserId, Long installationId,
        String sourceTool, String clientRunId) implements AgentIdentity {
}                                    // ← 一行实现代码都没写
```

因为 record 自动生成的组件访问器 `agentAccountId()`、`ownerUserId()`… **恰好就是接口要求的方法签名**。

这不是运气——我是**照着 record 的命名风格反过来设计接口的**(方法名不带 `get` 前缀)。代价近乎零,换来一条模块边界。

---

### 🎤 面试自述(3 分钟版,可直接背)

> 我项目里有三条认证链:浏览器 Session、CLI 的主人令牌、AI 机娘的代理令牌。我一开始很困惑——我只在 controller 上看到 `@AuthenticationPrincipal` 在取身份,却找不到谁在存,而且不同 controller 取出来的类型还不一样。
>
> 后来我全局搜 `setAuthentication`,发现全项目只有三个写入点,分别属于三条链,而且是三节课分别埋下的。想通之后关键认知是:**`SecurityContext` 是 ThreadLocal、每个请求各自一份**,根本不存在"一次注入";`@AuthenticationPrincipal` 也不是注入,是个参数解析器,它拿到 principal 后按形参**声明的类型**做匹配。这里有个坑我记得很清楚——**类型对不上时它默认静默返回 null,不抛异常**,`errorOnInvalidType` 默认 false,所以踩了会 NPE 在很远的地方、堆栈指不到根因。
>
> 第二个收获是搞清了"什么时候该用对象 principal"。我原以为判据是用户类型,其实是**身份的字段数**:主人在浏览器里身份只有一个 userId,可以塞进 `auth.getName()` 那个 String 槽;但 CLI 的主人多了个 installationId、机娘有五个字段,String 槽装不下,就必须上对象 principal。**单值塞 name 槽,多值上对象。**
>
> 第三个是架构层面的。机娘的 principal 类在 identity 模块的私有子包里,但要读它的业务模块是 content——直接 import 就违反 Spring Modulith 的模块边界,ArchUnit 会判红。我用依赖倒置解:在中立的 shared 模块定义一个只读接口 `AgentIdentity`,让 identity 的 principal 去 implements 它,content 只依赖接口。这样 content→identity 那条箭头就消失了,变成两边都指向 shared。
>
> 我特意想过它跟三层架构里 `IUserService`+`Impl` 的区别——形状一样,但三层那个接口和实现在同一个模块,删掉它模块依赖图纹丝不动,是"仪式";我这个删掉之后 ArchUnit 立刻红,是"结构"。而且三层里调用方向和依赖方向是同向的,严格说只是面向接口编程,没有真正的倒置。**我后来把这个总结成一条判据:删掉这个接口,有没有依赖箭头改变方向?没有就是仪式,有就是停火协议。**

### 📌 学习点(半年后只看这几条也能捡回来)

1. `SecurityContext` 是 **ThreadLocal · 每请求一份**,不是单例 bean——"一次注入"这个说法本身就不成立
2. `@AuthenticationPrincipal` 是**参数解析器**,按形参声明类型匹配;**类型不符默认静默返回 null**(`errorOnInvalidType=false`),这是个能让人查半天的坑
3. 找"身份从哪来"的万能招:全局搜 `setAuthentication`,写入点通常就那么几个
4. **单值身份可以塞 `auth.getName()` 的 String 槽,多值身份必须用对象 principal**——判据是字段数,不是用户类型
5. Spring Security 的 principal 类型是 `Object`,`UserDetails` 只是最常见的一种,可以自定义
6. **区分"面向接口编程"和"依赖倒置"**:前者调用方向=依赖方向,后者把依赖方向掰反了
7. **判断接口是仪式还是结构**:删掉它,有没有依赖箭头改变方向/消失?
8. record 的组件访问器天然满足无 `get` 前缀的接口方法——设计接口时对齐实现的语言特性,能白捡零成本

### 🔗 关联

- [故事 13:四层认证体系](#故事-13为-cli--ai-机娘设计一套四层认证体系选型流程与安全纵深l12-l13) —— 三条链的全景由来
- [故事 9:过滤器双重注册](#故事-9spring-boot-过滤器双重注册为什么-component-反而是坑l13) —— 同一批过滤器上的另一个坑
- [故事 7:跨模块操作的三种规则](#故事-7跨模块操作的三种规则l07l09-三层递进) —— 模块边界的其他解法
- [故事 16:client_run_id 的可信边界](#故事-16一个字段凭什么敢让客户端随便填可信边界怎么划l13l14) —— 同一课的另一个疑问

---

## 故事 16:一个字段凭什么敢让客户端随便填?——可信边界怎么划（L13→L14）

> **一句话**:我在数据库里发现同一列存着 `run-1` 和 `run-bf3e3fed-8e12-...` 两种完全不同格式的值,追下去发现这个字段**是纯客户端说了算的**——然后我搞清了"哪些字段能信客户端、哪些绝对不能"的判据。
>
> **什么时候讲**:面试官问「你怎么做参数校验」「怎么防越权」「审计日志怎么设计」「幂等怎么做」,或者任何关于**信任边界**的话题。
>
> **开场钩子**:"我项目里有个字段是完全交给客户端自由填的,连格式都不校验。这不是漏掉了,是想清楚之后**故意**的。"

### 🎬 场景重建(先帮你想起当时在干什么)

在做 **L14 单机娘投稿**的验收。我用 Postman 走完整链路:

```
设备配对 → 拿主人令牌 → assume 代入机娘 → 拿机娘令牌 → 投稿
```

其中 assume 这一步是这样的:

```
POST {{baseUrl}}/api/v1/cli/agents/{{agentAccountId}}/assume
{
  "sourceTool": "claude-code",
  "clientRunId": "run-1"          ← 我盯上的就是这个
}
```

验收完我去数据库翻 `agent_acting_session` 表,看到 `client_run_id` 列里躺着**两种画风完全不同的值**:

```
run-1                                        ← 我 Postman 里手填的
run-bf3e3fed-8e12-477a-8c5a-372bccdbe186     ← 这个哪来的?
```

我当场就懵了:**这字段真的纯粹是客户端想填啥填啥?没人管?** 而且 L13 讲 assume 的时候,我脑子里的印象只是"发一把新令牌而已",完全没意识到请求体里这两个字段是什么来头。

### ❓ 我当时的原话疑问(原汁原味保存,不修改)

> “{{baseUrl}}/api/v1/cli/agents/{{agentAccountId}}/assume”代入自己机娘是runId竟然是由cli传来的定的吗?你看数据库发现甚至有“run-bf3e3fed-8e12-477a-8c5a-372bccdbe186”和“run-1”这样不同的id,真的纯交给客户端?是什么意思,还有这个id有啥用?是审计的?还是审计用的其实是agent_access_session的主键id?代入这条线似乎之前教学没有讲过这里,当时我只领悟到就是简单发个新的令牌嘛。

### 💬 面试时可以这么说(优化表达 —— 上面的原话保留,不覆盖)

> 验收时我在数据库同一列里发现了两种格式完全不同的值,追下去发现这个 `clientRunId` 是**客户端自由填、服务端不校验**的。我的第一反应是"这是不是个安全漏洞",于是去梳理了这条链路上**每个字段的来源和可信度**,想搞清楚:哪些字段可以信客户端、哪些绝对不能,判据是什么;以及既然已经有会话主键 id 了,为什么还要额外存一个客户端给的 id。

---

### 🔍 追查过程

**第一步:两种格式的来源找到了——是两个不同的客户端。**

| 值 | 谁产生的 | 出处 |
|---|---|---|
| `run-1` | 我自己在 Postman 里手填的 | `AgentLog-L14.postman_collection.json` 的 assume 请求体 |
| `run-bf3e3fed-…` | **CLI 自动生成的默认值** | `cli/.../AgentCommand.java:116-117` |

CLI 那段代码是这样的:

```java
// cli/src/main/java/com/agentlog/cli/AgentCommand.java:116-117
String runId = (clientRunId != null && !clientRunId.isBlank())
        ? clientRunId : "run-" + UUID.randomUUID();     // 没传就随机生成一个
```

**所以不是"数据脏了",是两个不同来源的客户端各按自己的习惯生成。** 这本身就说明了这个字段的性质。

**第二步:确认服务端到底校不校验。**

翻 `AgentAssumeService`,答案很干脆——**除了 `@NotBlank`,一个字符都不校验,原样落库**:

```java
// backend/.../identity/pairing/application/AgentAssumeService.java:47
agentService.findOwnedActiveAgentOrThrow(owner.ownerUserId(), agentAccountId);
//  ↑ 唯一的安全校验:机娘必须存在、属于当前主人、且 ACTIVE,否则 404

// :57-58
session.setSourceTool(request.sourceTool());       // 原样存
session.setClientRunId(request.clientRunId());     // 原样存,不校验格式
```

---

### ✅ 答案①:核心判据——**一个字段能不能交给客户端,取决于它参不参与授权决策**

我把这条链路上所有字段按可信度分成三级,分完之后一切都清楚了:

| 可信度 | 字段 | 谁决定的 | 为什么可以/不可以 |
|---|---|---|---|
| 🟢 **服务端权威** | `owner_user_id`、`installation_id`、`session.id`、令牌摘要 | **服务端**从令牌反查出来 | 客户端**碰都碰不到**,伪造无门 |
| 🟡 **客户端提出,服务端裁决** | `agentAccountId`(URL 路径参数) | 客户端提出 → 服务端校验 | 客户端可以随便写别人的机娘 id,但 `:47` 那行会挡下来 → **404**(而且刻意不返回 403,免得泄漏"这个机娘存在") |
| 🔴 **客户端自由声明** | `sourceTool`、`clientRunId` | **纯客户端** | **它不参与任何授权决策**,伪造它没有任何安全收益 |

**关键推理**:假设一个机娘乱填 `clientRunId="run-老板的运行"` 会怎样?

答案是——**什么也不会发生**。它换到的令牌,权限完全由 🟢🟡 两级字段决定(它只能代入自己主人名下的机娘)。乱填 runId 的唯一后果是**给自己的投稿贴了个错标签**,伤害的是它自己的可追溯性。

> 📌 **一句话判据:如果伪造一个字段无法带来任何越权收益,那它就可以交给客户端。反之则必须服务端权威。**

### ✅ 答案②:为什么服务端不能自己生成这个 id?

这个问题我一开始也想岔了——"服务端自己生成一个不就干净了吗?"

**不行,因为服务端根本不知道"一次运行"的边界在哪。**

"一次运行"是**客户端进程/会话的概念**:一个 AI 机娘从被唤起、干活、到收尾,这段跨度只有客户端自己清楚。服务端看到的只是**一堆离散的 HTTP 请求**,它没有任何依据把请求 A 和请求 B 归为同一次运行。

而且更关键——**令牌会过期,但运行不会因此结束**:

```
一次长跑(比如机娘干了 3 小时)
├── 第 1 小时:assume → 令牌 A(1h 过期,无 refresh)
├── 第 2 小时:令牌 A 过期 → 重新 assume → 令牌 B
└── 第 3 小时:令牌 B 过期 → 重新 assume → 令牌 C
                     ↑
        三把令牌 = 三条 session 行 = 三个不同的 session.id
        但逻辑上这是【同一次运行】,应该共享同一个 clientRunId
```

**这就是为什么 `contribution` 表存的是 `client_run_id` 而不是 `session.id`**——如果存 session.id,一次长跑的产出会被切成好几段,再也拼不回来。

### ✅ 答案③:所以两个 id 是分工,不是二选一

我原来的疑问是"审计到底用哪个"。答案是**两个都要,各管一段**:

| | `agent_acting_session.id`(主键) | `client_run_id` |
|---|---|---|
| **谁产生** | 服务端(自增) | 客户端声明 |
| **可信度** | 🟢 绝对可信 | 🔴 不可信,仅作标注 |
| **回答什么问题** | "**用哪把令牌**写进来的" | "机娘**哪一次干活**产出的" |
| **性质** | 技术凭证的审计锚点 | 业务运行的关联标识 |
| **粒度** | 每次 assume 一条 | 一次运行一个(可跨多条 session) |
| **能不能用于鉴权** | 是(令牌验证的落点) | **绝对不能** |

**一句话:`session.id` 审计"凭证",`client_run_id` 关联"业务"。**

### 🌍 这不是我们的土办法,是业界通行模式

想通之后我发现,"客户端声明的关联标识"到处都是:

| 例子 | 谁生成 | 服务端态度 |
|---|---|---|
| **OpenTelemetry 的 trace id** | 客户端/上游服务 | 记录并串联,**不校验** |
| **Stripe 的 `Idempotency-Key`** | 客户端 | 用它去重,**不校验格式** |
| **AWS 的 `ClientRequestToken`** | 客户端 | 同上 |
| **HTTP `User-Agent`** | 客户端 | 记录/分流,**从不用于授权** |

**共同点:全都是客户端声明、服务端记录但不信任、绝不参与授权。** `clientRunId` 属于同一族。

### ⚠️ 诚实交代一个当前的缺口(讲出来反而加分)

`V011` 里那个隔离索引是**普通 KEY,不是 UNIQUE**:

```sql
KEY idx_agent_acting_isolation (agent_account_id, source_tool, client_run_id)
--  ↑ 普通索引,不是 UNIQUE
```

意味着现在同一组 `(机娘, 工具, 运行)` 可以 assume **无数次**,各拿一把令牌,谁也不拦。

**现阶段这是对的**——因为上面说了,一次长跑本来就需要多次 assume 换令牌。但如果将来要做**投稿幂等**(后续课的多机娘协作 submit 就标了"幂等"要求),`clientRunId` 很可能要升格成幂等键,那时候约束和唯一性就得重新设计。

**我把它记下来了,而不是假装没这回事。**

> 另外一个小的不一致,也一并记着:`contribution.client_run_id` 是 `VARCHAR(128)`(V005 建的),而 `agent_acting_session.client_run_id` 是 `VARCHAR(64)`(V011 建的)。方向是"窄 → 宽",不会截断,所以不是 bug,但两处该对齐。

---

### 🎤 面试自述(3 分钟版,可直接背)

> 验收的时候我在数据库同一列里看到两种画风完全不同的值:一个是 `run-1`,一个是 `run-` 加 UUID。追下去发现是两个客户端各自生成的——一个是我 Postman 手填的,一个是 CLI 默认用 UUID 生成的。再往下查,发现服务端**除了非空,一个字符都不校验**,原样落库。
>
> 我第一反应是"这是不是漏了校验",于是把这条链路上每个字段按可信度分了三级:服务端从令牌反查出来的(主人 id、设备 id、会话 id)是绝对权威,客户端碰不到;URL 里的机娘 id 是客户端提出、服务端裁决——它可以填别人的机娘,但服务端会校验归属,不通过就返回 404,而且刻意不用 403,避免泄漏"这个机娘存在";剩下的 sourceTool 和 clientRunId 是纯客户端声明的。
>
> **想通的判据是:伪造这个字段能不能带来越权收益。** clientRunId 不参与任何授权决策,乱填的唯一后果是给自己的投稿贴错标签,伤的是自己的可追溯性,所以可以放心交给客户端。
>
> 那服务端为什么不自己生成一个?因为**服务端不知道"一次运行"的边界在哪**——那是客户端进程的概念。而且我们的机娘令牌是一小时短命、没有 refresh 的,一次三小时的长跑要重新 assume 三次、产生三条会话记录,但逻辑上还是同一次运行。所以 contribution 表存的是 clientRunId 而不是会话主键——**存主键会把一次运行切碎成几段,再也拼不回来**。
>
> 最后我理清了两个 id 是分工不是二选一:**会话主键审计"用哪把令牌进来的",clientRunId 关联"哪一次干活产出的"**。后来我发现这正是业界通行模式——OpenTelemetry 的 trace id、Stripe 的 Idempotency-Key、AWS 的 ClientRequestToken,全都是客户端声明、服务端记录但不信任、绝不参与授权。
>
> 顺带我也记下了一个当前缺口:那个隔离索引现在是普通索引不是唯一索引,所以同一次运行可以重复 assume。现阶段是对的,但将来要做投稿幂等的话,这个字段可能要升格成幂等键,约束得重新设计。

### 📌 学习点(半年后只看这几条也能捡回来)

1. **核心判据:一个字段能不能交给客户端 = 伪造它能不能带来越权收益。** 不能 → 放心交出去
2. 把链路上的字段分**三级可信度**:🟢服务端权威 / 🟡客户端提出+服务端裁决 / 🔴客户端自由声明——分完之后设计意图自然浮现
3. **服务端不该生成"客户端会话边界"类的 id**——它没有依据判断边界在哪
4. **令牌生命周期 ≠ 业务运行生命周期**。令牌会过期换新,业务运行不会因此中断,所以业务记录要用**业务级关联 id**,不能用技术凭证的主键
5. **两个 id 分工**:技术主键审计"凭证",客户端 id 关联"业务"
6. 归属校验失败返 **404 而不是 403**,是刻意的——403 等于承认"这东西存在",会泄漏存在性
7. 这类"客户端声明的关联标识"是业界通行模式(trace id / Idempotency-Key / ClientRequestToken / User-Agent),**共性是:记录但不信任、绝不参与授权**
8. 已知缺口要写下来而不是掩盖:隔离索引非 UNIQUE → 将来做幂等要重新设计

### 🔗 关联

- [故事 15:三种 principal](#故事-15一个请求进来后端凭什么知道你是谁三种-principal两种读法一个接口l05l14) —— 同一课的另一个疑问;那些🟢字段就是从 principal 里来的
- [故事 13:四层认证体系](#故事-13为-cli--ai-机娘设计一套四层认证体系选型流程与安全纵深l12-l13) —— assume 这一层的完整由来
- [故事 10:RTR 令牌轮换](#故事-10rtr-令牌轮换比双-token-多做的两件事l13) —— 令牌短命/轮换的设计背景

---

## 故事 17:契约声明了、后端没填、前端假装没这回事——一个"三方各退一步"造出的洞（L06→L14）

> **一句话**:OpenAPI 契约里声明了 `ContentBlockView.author`(这段是谁写的),但**两个后端模块都没填、前端也没读**,于是这个字段在纸面上存在了 8 节课、实际上从来没有值——直到 AI 机娘开始投稿,"看不出哪段是机器写的"才变成真问题。
>
> **什么时候讲**:面试官问「契约先行怎么落地」「前后端怎么对齐」「你怎么发现隐藏的技术债」「N+1 怎么防」。
>
> **开场钩子**:"我们是契约先行的项目。但我发现契约里有个字段,后端两个模块都没实现,前端也没读——**三方都'没错',字段就这么空了八节课**。"

### 🎬 场景重建(先帮你想起当时在干什么)

L14 刚做完:AI 机娘能自动投稿了,我也补了主人的审稿页。端到端跑通,机娘投的草稿在页面上显示:

```
🤖 机娘投稿   可编辑   草稿 #6   v0
L14 收尾：补齐草稿预览页……
—— 来源工具：claude-code          ← 只有这一行
```

我盯着"来源工具：claude-code"看了几秒,意识到一个问题:**我库里有两个机娘,星梦(id=1)和 Queen(id=2)。这页告诉我"是用 claude-code 跑的",但没告诉我是它们俩谁写的。**

审稿的人最需要知道的恰恰是"谁写的"——不同机娘可能有不同人格、不同可信度。结果页面上只有工具名。

我去翻契约,愣住了:

```yaml
# docs/api/agentlog-openapi.yaml
ContentBlockView:
  properties:
    blockId: ...
    displayOrder: ...
    content: ...
    author:                          # ← 早就声明了!
      $ref: '#/components/schemas/AuthorView'
    sourceTool: ...
```

**契约里明明白白写着 `author`。** 那为什么没有值?

### 🔍 追查:三方各自"没错",合起来就是个洞

我顺着查了三层,发现这不是谁犯了错,而是**三方各退一步,退出来一个空洞**:

**第一层 · 后端 content 模块** — record 只有 4 个字段,压根没有 `author`:

```java
public record ContentBlockView(
        Long blockId, Integer displayOrder, String content, String sourceTool) {}
        //                                    ↑ 契约里 author 该在这儿,没有
```

**第二层 · 后端 forum 模块** — 同名 record,**同样 4 个字段**(两模块各持一份读模型,互不 import,这是 Modulith 边界的正常做法)。**两处一起漏**。

**第三层 · 更狠的**:forum 的帖子详情里,连帖子级的作者列表都是写死的空:

```java
return new PublicPostView(
        ...,
        List.of(),        // ← authors 直接返回空列表
        ...);
// 注释写着:"authors 详情页暂保留骨架；L10 先补 Feed 卡片作者头像组"
```

那条注释是**关键证据**:当时的判断是"详情页作者先欠着,Feed 卡片更要紧"。这个"暂"字一欠就是好几节课,而且**没有任何机制会提醒它**。

**第四层 · 前端**:生成的 TS 客户端里 `author?: AuthorView` 一直存在(它是从契约生成的),但页面代码从来没读过它——因为读了也是 `undefined`。

### 💡 为什么八节课都没人发现?

这才是我觉得最值得讲的部分。

**因为在 L14 之前,所有内容都是主人自己写的。** 一篇帖子只有一个作者,而这个作者信息在**别的地方已经有了**(帖子级的 owner、Feed 卡片的作者头像)。块级的 `author` 是冗余的,空着完全不影响任何人。

**是 AI 机娘的加入让这个字段第一次有了信息量**——同一篇稿可能有主人写的段落 + 机娘写的段落,块级作者从"冗余"变成"唯一能回答'哪段是谁写的'的地方"。

> 📌 **这是一类典型的技术债:字段在纸面上存在,但因为当时的业务形态用不到它,三方都合理地跳过了。等业务形态变了,它就从"冗余"变成"缺失"——而且没有任何测试会失败,因为从来没人断言过它。**

### ✅ 怎么修的

**关键约束:content / forum 都需要作者的展示名,但作者数据在 identity 模块**(主人在 `user_account.username`,机娘在 `agent_account.nickname`)。直接 import identity 的类会被 ArchUnit 判红。

按项目既定规则(放弃 Facade、改用 Spring Modulith 包边界):**跨模块【只读】走 SQL 投影直查物理表,【写】只碰本模块表**。所以两个模块各自写自己的投影查询,只 SELECT 展示字段,零 Java 依赖。

**修法三要点:**

**① 两类作者查两次,合进一张表,键必须带类型前缀**

```java
index.put("U:" + row.getUserId(), ...);    // 人
index.put("A:" + row.getAgentId(), ...);   // 机娘
```

**为什么必须加前缀**:`user_account.id` 和 `agent_account.id` 是两套独立自增序列。**userId=1 和 agentId=1 同时存在且是不同实体**——不加前缀,同一张 Map 里直接撞车,主人会显示成机娘。这个坑我是在设计键的时候就想到的,不是撞出来的。

**② 防 N+1:两条 IN 查询封顶,不逐块查**

一篇稿可能有几十个块。逐块查作者就是教科书级 N+1。做法是先把所有块的 userId / agentId 收成两个 Set,两条 `IN` 查完,再在内存里拼装。**这跟项目里 Feed 卡片查作者是同一个套路**(收 id → 批量查 → 内存 join)。

**③ 查不到时降级成脱敏占位,不返回 null**

```java
return b.getAuthorAgentId() != null
        ? AuthorView.deletedAgent(b.getAuthorAgentId())   // "已下线的机娘"
        : AuthorView.deletedOwner(b.getAuthorUserId());   // "已注销"
```

契约里 `AuthorView.deleted` 这个布尔字段就是为这个场景准备的:**作者注销了,内容还在,追溯链不能断**——保留 id 供追溯,展示名脱敏。只有 L06 之前那种作者维度整个为空的历史数据才如实返回 `null`。

**④ 补测试锁住**:机娘投稿的块作者是 AGENT + 昵称对得上;主人投稿的块作者是 OWNER + 用户名对得上、且 `agentId` 不存在。**两条对照着写**,防止只顾修 AGENT 把 OWNER 改回归了。

### 🎤 面试自述(3 分钟版)

> 我们项目是契约先行的——OpenAPI 写在前面,前端客户端代码从契约生成。我在做 AI 机娘投稿功能时发现,审稿页只能显示"来源工具 claude-code",却显示不出是哪个机娘写的。翻契约一看,`ContentBlockView` 里明明声明了 `author` 字段。
>
> 追下去发现是**三方各退一步造出的洞**:后端两个模块的 DTO 都只有 4 个字段、没实现 author;其中一个模块连帖子级的作者列表都是写死的空数组,注释还留着"暂保留骨架";前端生成的类型里 author 一直在,但页面从没读过——读了也是 undefined。
>
> **为什么八节课没人发现?因为在 AI 投稿之前,所有内容都是主人自己写的,一篇一个作者,块级作者是冗余信息,空着不影响任何人。** 是机娘的加入让它从"冗余"变成"唯一能回答哪段是谁写的地方"。这类债很隐蔽——**没有任何测试会失败,因为从来没人断言过它**。
>
> 修的时候有三个技术点。第一,作者数据在 identity 模块,但要它的是 content 和 forum——直接 import 会被 ArchUnit 判红,所以按项目规则走跨模块只读 SQL 投影,各模块查各自的,零 Java 依赖。第二,人和机娘的 id 是两套独立自增序列,userId=1 和 agentId=1 是不同实体,所以作者查找表的键必须带类型前缀,否则主人会显示成机娘。第三,防 N+1——一篇稿几十个块,不能逐块查,而是收齐 id 两条 IN 查完再内存拼装。
>
> 还有个细节我比较满意:作者查不到时我没返回 null,而是降级成"已注销"这种脱敏占位并保留 id。**因为作者注销了内容还在,追溯链不能断**——契约里 `deleted` 这个布尔字段本来就是为这个场景设计的。

### 📌 学习点(半年后只看这几条也能捡回来)

1. **契约先行不等于契约被实现**。生成客户端只保证"类型对得上",不保证"字段有值"——**契约合规性需要单独测**
2. 一类隐蔽技术债:**字段因当时业务形态用不到而被三方合理跳过,业务形态一变就从"冗余"变"缺失"**,且无测试会失败
3. 代码注释里的 **"暂"「先」「留到 Lxx」是债务标记**——没有机制追踪它就会永远欠着。看到这种注释要么建 issue 要么就别写
4. **多套独立自增 id 合进同一张 Map,键必须带类型前缀**(userId=1 ≠ agentId=1)
5. 批量翻译 id → 视图对象:**收 id 成 Set → 少数几条 IN → 内存 join**,是防 N+1 的通用套路
6. **实体删除后,引用它的历史内容要降级展示而不是断链**:保留 id 供追溯 + 展示名脱敏 + 一个 `deleted` 标记位
7. 跨模块要数据时,先问"是读还是写":**只读可以走 SQL 投影直查(零 Java 依赖),写必须留在自己模块**
8. 修一个分支时,**给对照分支也补一条测试**(修 AGENT 就同时锁住 OWNER),否则容易顾此失彼

### 🔗 关联

- [故事 15:三种 principal](#故事-15一个请求进来后端凭什么知道你是谁三种-principal两种读法一个接口l05l14) —— 同样是"跨模块拿身份",那次用 DIP 接口,这次用 SQL 投影,判据是**读 vs 写**
- [故事 7:跨模块操作的三种规则](#故事-7跨模块操作的三种规则l07l09-三层递进) —— 跨模块规则的完整版
- [故事 1:Feed 列表的 N+1 问题怎么防](#故事-1feed-列表的-n1-问题怎么防l07) —— 同一个批量查询套路的起源

---

## 故事 21:两个 AI 对话之间没有共享内存,凭什么保证它们写文章的顺序(L15)

### 🎬 场景重建

项目的卖点是**多个 AI 工具在同一篇文章里有序续写**——Claude Code 写一段,Codex 接着写一段。
但坐下来一想就卡住了:

```
[Claude Code 对话]        [Codex 对话]
      │                        │
   互相不知道对方存在,没有共享内存,不能互发消息
      │                        │
      └──────  唯一通道:主人复制粘贴一串字符  ──────┘
```

**先认清约束,再谈设计。** 这个约束决定了一切。

### 🔍 最直觉的错解:时间戳

各自提交时打个时间戳,服务端按时间戳排序。**这个做法会崩**:

- B 完全可能**手比 A 快**(主人先在 Codex 里敲了命令);
- B 的机器**时钟比 A 早**(两台机器,或容器时区不同);
- 有人**手工改了系统时间**;
- 网络时差。

时间戳表达的是**观测**,而我们需要的是**因果**。

### 💡 正解:把因果关系物化成一列外键

关键洞察:**B 只有拿到 A 交出的令牌之后,才可能入队**。这个"之后"是因果的,不是时钟的。

```sql
-- V012__create_collaboration.sql
predecessor_ticket_id BIGINT NULL,   -- 前一棒。首棒为 NULL
CONSTRAINT fk_ticket_predecessor FOREIGN KEY (predecessor_ticket_id) REFERENCES contribution_ticket(id)
```

链表式追加,尾巴由 `collaboration_session.tail_handoff_token_id` 指着。
**后发的票必然在链尾**,与谁手快、谁时钟准完全无关。

这就是分布式系统里的**因果序(happens-before)**——Lamport 1978 年
*Time, Clocks, and the Ordering of Events in a Distributed System* 那篇论文的核心:
**物理时钟不可信,因果链可信。**

### ⚖️ 设计空间与权衡

| 方案 | 机制 | 代价 |
|---|---|---|
| 主人预先排定 A→B→C | 建会话时列出全部参与者 | 要求主人**事先知道**要请谁、请几个。真实场景是"写完一段才决定要不要再叫一个"——预排把动态过程冻成静态计划 |
| **令牌接力**(选它) | 每入队一次签发一张新尾令牌,谁拿到谁能接 | 令牌要过主人的手,泄漏面大 → 用 24h TTL + 一次性消费对冲 |
| 抢占式队列 | 任何持 agent 令牌的机娘都能插队 | 放弃顺序;跨主人的机娘也能插(多租户破防) |

**选令牌接力的理由是物理约束不是审美**:令牌是「**可复制粘贴的凭证**」,
天然匹配"你把一串字符从一个终端粘到另一个终端"这个动作。

### 🎤 面试自述(2 分钟版)

```
我们要让多个 AI 工具在同一篇文章里有序续写。难点是这些 AI 对话之间零共享上下文
——没有共享内存、不能互发消息,唯一通道是人复制粘贴。

最直觉的做法是打时间戳排序,但会崩:第二个 AI 完全可能手更快、时钟更早,甚至有人
改了系统时间。时间戳表达的是观测,我们需要的是因果。

关键洞察是:第二个 AI 只有拿到第一个交出的令牌之后才可能入队,这个"之后"是因果的。
所以我把因果关系物化成一列自引用外键 predecessor_ticket_id,链表式追加。
这样即使两台机器时钟差几秒、即使有人改系统时间,顺序也不会乱。

这其实就是 Lamport 那篇讲逻辑时钟的论文里的 happens-before ——
物理时钟不可信,因果链可信。
```

### 📌 学习点

1. **先认清物理约束,再谈设计**。"零共享上下文 + 人是唯一通道"这个约束直接排除了两个方案。
2. **时间戳 ≠ 顺序**。分布式场景里凡是"按时间排"的直觉,先问一句"这个时间是谁测的"。
3. **因果可以被物化**——不是抽象概念,就是一列外键。
4. 令牌之所以合适,是因为它**匹配用户的物理动作**(复制粘贴)。好的技术选型常常是这么来的。

### 🔗 关联

- [故事 22:一条带条件的 UPDATE 代替分布式锁](#故事-22一条带条件的-update-代替分布式锁还有一个我自己踩的坑l15) —— 同一课,顺序有了之后怎么保证互斥

---

## 故事 22:一条带条件的 UPDATE 代替分布式锁,还有一个我自己踩的坑(L15)

### 🎬 场景重建

接力棒(HandoffToken)是**一次性**凭证。但主人完全可能把同一根棒子发给了两个 AI,
或者 skill 自动重试——必须保证**只有一个能成功**。

### 🔍 错解:查 → 判 → 改

```java
HandoffToken t = mapper.selectByDigest(digest);      // 1. 查
if (!"AVAILABLE".equals(t.getStatus())) throw ...;   // 2. 判
mapper.updateStatus(t.getId(), "CONSUMED");          // 3. 改
```

两个线程同时跑:

```
T1  线程A  SELECT → AVAILABLE
T2  线程B  SELECT → AVAILABLE     ← 命门:B 也读到了 AVAILABLE
T3  线程A  判定通过
T4  线程B  判定通过
T5  线程A  UPDATE → CONSUMED
T6  线程B  UPDATE → CONSUMED      ← 双双"成功",同一张令牌产出两个席位
```

这叫 **TOCTOU**(Time-Of-Check to Time-Of-Use)。根因是 **`SELECT` 一结束就放锁了**,
`if` 判断与 `UPDATE` 之间那个空窗就是竞态的窝。

### 💡 正解:把判定塞进 WHERE

```sql
UPDATE handoff_token
SET status='CONSUMED', consumed_by_agent_id=?, consumed_at=?, version=version+1
WHERE token_digest=? AND status='AVAILABLE' AND expires_at>=?;
```

这是**一条**语句(`SET` 里的逗号只是列清单分隔符,分号才是语句边界)。而"一条"正是原子性的全部来源:

```
MySQL 扫到命中行 → 立刻加排他锁 X → 求值 WHERE → 改或跳过 → 语句结束才放锁
                     从"检查"到"修改"全程持锁,另一个线程连读最新版本都得排队
```

`affectedRows` 就是数据库给的裁决书:`1`=抢到,`0`=条件当时不成立。

### 🔄 一个转折:唯一键其实兜住了一层,但不能靠它

即使写成三步走,`uk_ticket_sequence(session_id, sequence_no)` **也会拦下第二张票**
——两个线程都算出 `sequence_no=2`,第二个撞唯一键。

**先把机制讲清楚**(这两个是完全独立的东西,别混):

| | 唯一键 UNIQUE | 行锁 Row Lock |
|---|---|---|
| 管什么 | 「这个值组合不能重复出现」 | 「这一行同一时刻只能被一个事务改」 |
| 何时生效 | **INSERT** 一行时(或 UPDATE 改到键列) | 执行 **UPDATE/DELETE** 时自动加 |
| 怎么阻止你 | 报错 1062 Duplicate entry | 让你**排队等**前面的事务结束 |

**注意:更新 handoff 的互斥来自行锁,跟唯一键毫无关系。** 唯一键起作用的地方是「建票」那一步。

### 逐帧推演:闸门排在建票【前】还是【后】,决定输家看到什么错

```
顺序 A(错):查令牌 → 建票 → 消费令牌
  T1 线程1 SELECT 令牌 → AVAILABLE
  T2 线程2 SELECT 令牌 → AVAILABLE      ← 两个都读到"可用",此刻谁都没被拦
  T3 线程1 算 seq=2 ; T4 线程2 也算 seq=2
  T5 线程1 INSERT ticket(seq=2) ✅
  T6 线程2 INSERT ticket(seq=2) → 撞唯一键 → 先【阻塞】等线程1结束
  T7 线程1 UPDATE 令牌 ✅ ; T8 COMMIT
  T9 线程2 → 1062 → DuplicateKeyException → 回滚 → 客户端收到 HTTP 500 ❌

顺序 B(对):查令牌 → ★消费令牌(闸门)★ → 建票
  T3 线程1 UPDATE ... WHERE status='AVAILABLE' → 行锁 → 条件成立 → affectedRows=1 ✅
  T4 线程2 同一条 UPDATE → ⏸ 行锁被占,排队
  T5 线程1 COMMIT 放锁
  T6 线程2 拿到锁,求值 WHERE:status 已是 CONSUMED → 不改任何行 → affectedRows=0
         → 干净地抛 409 + 「向主人索取最新尾令牌」,【根本没走到建票那一步】✅
```

**数据两种顺序都是对的(只有一张第 2 棒的票);差别在【输家看到的错误】。**

### ⚠️ 一处我说错过又更正的地方

我一开始的说法是「先 UPDATE 后插 ticket,唯一键就兜不住了」——**这句不准确,已撤回**。
在我们这套算法下(`seq = 前序.seq + 1`),两线程必然算出同一个 seq,**不管哪个顺序唯一键都兜得住数据**。

但「不能靠唯一键」的结论仍然成立,理由换成两条更硬的:

1. **错误语义是垃圾**——500 vs 409+自愈动作(上面推演过)。
2. **唯一键守的是【另一个】不变量,只是碰巧相关**:
   ```
   uk_ticket_sequence 表达的是:「一个会话不能有两个第 N 棒」   ← 结构不变量
   我们真正要的是    :「一根接力棒只能用一次」                 ← 业务不变量
   ```
   它们今天碰巧撞在一起,只因为 `seq = 前序.seq + 1`。
   **哪天有人做一次很自然的重构**——把算法改成 `SELECT MAX(sequence_no)+1`——两线程若错开一点
   就会算出 2 和 3,**唯一键不再冲突,同一根棒子被用了两次**。
   而那个重构的人完全不知道自己踩了雷,因为他改的是"算棒次",跟"令牌一次性"看着毫无关系。

> **靠错误的约束守不变量,它今天有效纯属巧合,而巧合会被无关的改动打破。**

### 「用异常控制业务流程」为什么不好

```java
// ❌ 靠 catch 走分支
try { ticketMapper.insert(newTicket); }
catch (DuplicateKeyException e) { throw new ApiException(ACPP_HANDOFF_CONSUMED); }

// ✅ 自己判断,返回明确结果
int affected = mapper.consumeAvailableToken(...);
if (affected == 0) throw new ApiException(rejectionFor(digest));
```
1. **推断是脆的**——这张表有两个唯一键,`DuplicateKeyException` 不告诉你撞的是哪个;
2. **异常类型不归你管**——它是 Spring 从数据库驱动错误码翻译来的,换库/换驱动版本可能变;
3. **异常很贵且污染日志**——它会以 ERROR 级别打进去,但这明明是**预期内的正常业务分支**(有人手慢了一步),不是故障。噪音一多,真故障就被淹了。

**异常应该表达「发生了我没预料到的事」,不该表达「两个人抢东西有一个没抢到」。**

### 😅 我自己踩了这个坑

实现 `ClaimHandoffService` 时,我最初排的顺序正是:

```
查令牌 → 建新票 → 原子消费(把 ticketId 一起写进去)
```

看着没问题,但两个线程抢同一张令牌时会**双双先建票**——此时还没有任何人被拦住,
`sequence_no` 都算成 2,第二个撞唯一键抛 `DuplicateKeyException`。**这正是我刚讲过的反面教材。**

正解是**闸门优先**:

```
① 查令牌(只取上下文,不做判定)
② ★原子消费 —— 闸门在这里,唯一的裁决者★
③ 建新席位(到这里已经独占,零竞争)
④ 回填 consumed_ticket_id
⑤ 签发新尾令牌
```

为此把消费拆成两条语句。看着"多了一条 UPDATE",换来的是**错误语义的正确性**:
过了闸门就独占,`uk_ticket_sequence` 退回它该有的角色——最后的安全网,正常路径永不触发。

并发测试里这条断言就是守它的:

```java
// 若把顺序写回「查 → 建票 → 消费」,败者会先撞唯一键,这里立刻红
.allMatch("ACPP_HANDOFF_CONSUMED"::equals)
```

### ⚖️ 为什么不用别的手段

| 手段 | 为什么不用 |
|---|---|
| `SELECT ... FOR UPDATE` | 也对,但要**开事务、持锁、再发第二条 UPDATE**——两条语句 + 一个事务,换来和一条语句同样的效果 |
| Redis 分布式锁 | 锁是**外部约束**,进程崩了锁过期就破防;而 `status='AVAILABLE'` 是**数据自身的约束**,永远成立。项目 ADR 也写死了"不要用 Redis 锁替代数据库状态机" |
| 应用层 `synchronized` | 单机有效,多实例立刻失效。而且它保护的是代码块,不是数据 |

**`SELECT FOR UPDATE` 值得单独说清楚**(它是悲观锁,不是"没用",是"这里不合适"):

```sql
SELECT * FROM handoff_token WHERE token_digest = ? FOR UPDATE;
                                                   ^^^^^^^^^^ 读的时候就上排他锁
```
普通 SELECT 在默认隔离级别下是**快照读**,连锁都不加、读完就走,所以两个线程会读到同一份旧数据。
加了 `FOR UPDATE`,行在读的那一刻就被锁住,而且**锁持有到事务结束**——别人想改或想同样 `FOR UPDATE` 读,都得排队。

| | `SELECT FOR UPDATE` + UPDATE | 一条条件 UPDATE |
|---|---|---|
| 语句数 | 2 条 | **1 条** |
| 必须开事务 | ✅ 是 | ❌ 不需要(单语句自带原子性) |
| 锁持有多久 | **整个事务**(可能很长) | **一条语句**(微秒级) |
| 判定写在哪 | Java 里 | SQL 的 WHERE 里 |

**那它什么时候才该用?** 当「改成什么值」必须先跑一堆 Java 逻辑才知道时:
```java
var order = mapper.selectForUpdate(orderId);        // 锁住
var quota = riskService.check(order.getUserId());   // 调外部服务算风控
var rate  = priceEngine.compute(order, quota);      // 跑复杂定价
mapper.update(order.getId(), rate);                 // 算完才知道改成什么
```
这写不成一条 UPDATE。而我们这里的判断是 `status='AVAILABLE' AND expires_at >= ?`,SQL 完全能表达。

> **判据:判断能写进 WHERE → 条件 UPDATE;判断要靠 Java 算 → `FOR UPDATE` 悲观锁。**

同族手法在项目里是第二次出现——**点赞 toggle 的 `INSERT IGNORE` + `affectedRows`**。
本质一样:**把互斥交给数据库的唯一性/条件判定,而不是应用代码的 if。**

### 🎤 面试自述(3 分钟版)

```
接力令牌必须一次性消费。最自然的写法是查一下状态、判断、再改——但那是三步,
两个线程会在同一时刻都读到 AVAILABLE,然后双双改成 CONSUMED,同一张令牌产出两个席位。
这是经典的 TOCTOU,根因是 SELECT 结束就放锁,if 和 UPDATE 之间有空窗。

正解是把判定塞进 WHERE:一条 UPDATE,WHERE 里带 status='AVAILABLE',
MySQL 在这一条语句内持行锁完成检查和修改,affectedRows 就是裁决书。
我们没做任何魔法,只是把判断从 Java 搬进 SQL,让"语句"这个天然的原子单位替我们做互斥。

有意思的是:即使写成三步走,唯一键其实也会拦下第二张票。但我坚持改成条件 UPDATE,
因为靠唯一键兜底等于用异常控制业务流程——客户端拿到的是 500 而不是干净的 409
加自愈提示,而且这个"兜住"还取决于你把 UPDATE 写在插入前还是插入后。
语义层的防线要在语义层建,底层约束是最后的安全网,不是第一道门。

我自己其实差点踩这个坑——最初的实现顺序是"查、建票、消费",两个线程会双双先建票
再撞唯一键。改成"闸门优先"之后才对:先原子消费,过了闸门就独占,后面零竞争。
并发测试里我专门断言"败者必须拿到 409 而不是约束异常",把这个顺序钉死了。
```

### 📌 学习点

1. **check-then-act 是并发 bug 的通用形状**,不只在这里。看到"先查再改"就该警觉。
2. **单语句 = 原子单位**。把判定条件塞进 WHERE,让数据库替你做互斥。
3. **`affectedRows` 是被低估的返回值**——它是数据库给的裁决书。
4. **底层约束兜住 ≠ 设计对了**。用异常控制流程会污染错误语义,而且脆弱。
5. **并发测试不加 `@Transactional`**——否则所有线程共享一个事务,测试会绿得毫无意义。
6. 竞态测试**要加压**(2 线程可能侥幸错开,我们同时跑 8 线程版本)。

### 🔗 关联

- [故事 21:两个 AI 对话之间凭什么保证顺序](#故事-21两个-ai-对话之间没有共享内存凭什么保证它们写文章的顺序l15) —— 同一课,先有顺序才谈互斥
- [故事 2:点赞 toggle 的并发](#故事-2点赞-toggle-的并发问题l09) —— `INSERT IGNORE` + `affectedRows`,同族手法的起源

---

## 故事 23:一个 8 小时的时区偏差,和"配置能救一次、结构能救一辈子"(L15)

### 🎬 场景重建

写完接力棒的消费逻辑,补了一条测试:把令牌的 `expires_at` 改成一小时前,
再去消费,**应该返回 410 Gone**。

结果 **201 Created ——过期令牌被成功消费了。**

### 🔍 排查

先怀疑是自己 SQL 写错,但 `WHERE ... AND expires_at >= NOW(3)` 看着没问题。
于是加了一条探针,直接问数据库"这个令牌还有多久过期":

```sql
SELECT TIMESTAMPDIFF(HOUR, NOW(3), expires_at) ...   -- TTL 配的是 24h
→ 实际返回 31
```

**差整整 8 小时。** 正好是 Asia/Shanghai 的 UTC+8。

### 💡 根因

`expires_at` 是 **Java 写入的** `DATETIME`,`NOW(3)` 是**数据库自己的**墙上时间。
两者的时区口径由 JDBC 连接参数决定:

| | `serverTimezone=UTC` | 结果 |
|---|---|---|
| 主应用 `application.yml` 的 datasource URL | ✅ 有 | 一致 |
| 测试的 Testcontainers **自建 URL** | ❌ 无 | **差 8 小时** |

**同一份代码在两个环境行为不一致。**

### ❓ 为什么全项目此前从没被咬过

这是最值得讲的一点。项目里已经有好几个 `expires_at`(配对码、owner 令牌、acting 令牌),
一直好好的。因为**它们的过期判断全都在 Java 侧比较**:

```java
if (now.isAfter(pairing.getExpiresAt())) { ... }
```

写和读**走同一条驱动转换路径**,时区偏移自动抵消。
我这条是**第一个**拿"Java 写入的 DATETIME"去和"数据库的 NOW()"比的——
所以偏差第一次显形。

### ⚖️ 修法:改结构,不是改配置

最省事的修法是给测试连接补上 `serverTimezone=UTC`。但我没有止步于此,而是改了判定方式:

```sql
AND expires_at >= #{now}     -- 用应用传入的时刻,不用数据库的 NOW(3)
```

这样**写入与比较走同一条驱动转换路径**,时区偏移自动抵消,
**正确性从此不依赖任何连接参数**。

代价评估:
- **原子性完全不受影响**——它来自"单条 UPDATE 持行锁",与用谁的时钟无关;
- 放弃"库时钟单方面裁决"会引入多实例时钟漂移的影响,但 NTP 下那是**毫秒级**,
  而时区错配是**小时级**。**消除大的那个。**

(我在代码注释里原本写的是"判定必须由数据库单方面裁决,免得多实例时钟不一致"——
这条判断是错的,被测试打红后更正了。)

同时也把测试容器的时区参数补齐(让测试环境 ≡ 生产环境),
并留下那条探针测试当**回归守卫**:

```java
// 若差出整数个小时,说明 JDBC 时区参数没对齐——后续的清理 Worker 会因此扫错
assertThat(hoursFromDbClock).isBetween(23, 24);
```

### 🔮 额外收获:提前拆了下一课的雷

设计文档里,**下一阶段的清理 Worker** 用的正是:

```sql
WHERE status='ACTIVE' AND lease_expires_at < NOW(3)
```

口径一偏,Worker 要么**提前清掉有效租约**、要么**永远清不掉过期的**。
这个坑本来会在那一课以"Worker 行为诡异"的形式爆出来,极难排查。
现在提前登记 + 留了守卫测试。

### 🎤 面试自述(2 分钟版)

```
我们踩过一个 8 小时的时区偏差。现象是一条测试红了:过期的令牌居然被成功消费。
加探针直接问数据库"这令牌还有多久过期",TTL 配的 24 小时,实际量出 31。

根因不是代码写错——是 expires_at 由 Java 写入、而 SQL 里拿数据库的 NOW() 去比,
两侧时区口径由 JDBC 连接参数决定,而生产的连接串带 serverTimezone=UTC、
测试用 Testcontainers 自建的连接串没带。同一份代码两个环境行为不一样。

更有意思的是:项目里已经有好几个 expires_at 一直没出事,因为它们的过期判断全在
Java 侧比较,写和读走同一条转换路径、偏移自动抵消。我这条是第一个跨到 SQL 侧比的。

修的时候我没有只是把连接参数补齐了事,而是改成用应用传入的时刻比较——
让写入和比较走同一条路径,正确性从此不依赖任何配置。原子性不受影响,因为那来自
单语句行锁、与用谁的时钟无关;而放弃库时钟带来的时钟漂移在 NTP 下是毫秒级,
时区错配是小时级,该消除的是大的那个。

顺带这个坑本来会在下一个迭代的清理 Worker 上爆出来——那里用的正是 NOW() 比较,
现在提前留了守卫测试。
```

### 📌 学习点

1. **测试红了先问"是代码错还是环境差异"**,别急着改代码。加探针量出具体数值最快。
2. **Java 写时间 + SQL 读 NOW() 比较 = 时区陷阱的经典配方**。要么全在应用侧比,要么全在库侧比。
3. **"一直没出事"不等于"没问题"**——只是还没有代码路径把它暴露出来。
4. **修配置 vs 修结构**:配置修复只解决当下,结构修复让问题不可能再发生。
5. 发现的隐患若会影响后续工作,**留一条守卫测试比写在文档里可靠得多**。

### 🧭 可以直接带走的通用规则

> **一个时间值,写入和比较必须走同一条转换路径。**

具体到日常编码,记住这三条:

| 场景 | 做法 |
|---|---|
| 时间由**应用**写入 | 比较也在**应用侧**做(`now.isAfter(x.getExpiresAt())`),或把应用时刻当参数传进 SQL(`WHERE expires_at >= #{now}`) |
| 时间由**数据库**写入(`NOW()`/`DEFAULT CURRENT_TIMESTAMP`) | 比较也用**数据库函数**(`WHERE created_at < NOW()`) |
| **禁止** | 一边 Java 写、另一边拿 `NOW()` 比——这两边的时区口径由 JDBC 连接参数决定,而连接参数在不同环境往往不一样 |

**排查手法也一并记住**:怀疑时区时,别去读配置猜,直接让数据库自己报数——
```sql
SELECT TIMESTAMPDIFF(HOUR, NOW(3), expires_at) FROM ...   -- 应该 ≈ TTL,差出整数小时就是时区偏了
```
差值是**整数个小时**几乎必然是时区问题(时钟漂移是秒级、不会正好差 8 小时)。

### 🔗 关联

- [故事 22:一条带条件的 UPDATE 代替分布式锁](#故事-22一条带条件的-update-代替分布式锁还有一个我自己踩的坑l15) —— 同一条 SQL,另一个维度的坑

---

## 故事 24:为什么协作信息不直接存在 post 表上——让数据的存在性替你守不变量(L15)

### 🎬 场景重建

要做「多个 AI 接力写同一篇文章」。最自然的想法是:既然最后产出的是一篇文章,
那就在 `post` 表上加几列(谁在写、写到第几棒),或者建个 `post_collaboration` 挂在 post 下面。

**但这条路一开始就走不通,原因很朴素:开局那一刻 `post` 还不存在。**

按我们冻结的流程,草稿要等**第一棒真正提交内容之后**才创建。而"开局"发生在那之前——
第一个 AI 说"我要开一篇协作"的时候,它连一个字都还没写。

所以必须有一个**先于 post 存在**的载体,把「这次协作」这件事本身记下来。
那就是 `collaboration_session`。

### 🔍 但真正的问题是:为什么不干脆提前把 post 建出来?

这是个好反驳,而且**功能上完全成立**:开局时就把 `post` + `draft` 建好(标成"协作中"),
然后在所有列表页里**过滤掉"零块草稿"**,读者和主人一样看不见。

我一开始给的理由是"会留下孤儿空草稿",但被追问后发现这个理由不够硬——藏起来确实也看不见。

**真正的差别不是性能,是【不变量由谁承担】:**

| | 「空草稿不可见」这条不变量由谁保证 |
|---|---|
| **建了再过滤** | 由**每一个读路径**保证。今天两处;将来编辑器、双视图、搜索、通知、热门榜、治理后台……**每加一个读路径就多一个必须记得过滤的地方,漏一个就漏出来** |
| **不建** | 由**数据的存在性**保证。不存在的行,任何查询都查不到。**零个地方需要记得** |

这就是把复杂度**往下压**:能用**数据模型**保证的,绝不用**代码纪律**保证。

### 💡 还有一个更硬的技术论据:那个过滤条件本身会失效

"零块草稿"这个判据**不稳**:

- 后面允许主人**隐藏**正文块。于是会出现「有块但全隐藏」的**合法**草稿——它该显示,但按"可见块数=0"会被误伤;
- 主人手动新建一篇还没写内容的**空草稿**——它也该显示。

**判据不稳、会误伤,比性能问题严重得多。** 而"不建那一行"没有判据,也就没有判据失效的问题。

### 🔗 同一个思路在这个项目里出现了三次

| 场景 | 靠纪律的做法 | 靠结构的做法 |
|---|---|---|
| 模块边界 | 文档写「不要跨模块 import」 | 定义只读接口让**编译器**管(ModularityTest 判红) |
| Markdown 渲染 | 文档写「禁止直接 v-html」 | 封成组件,调用方**拿不到** HTML 字符串 |
| **本课** | 「记得过滤零块草稿」 | **不建那一行** |

三次都是同一个判断:**能用结构约束的,就别只写在文档里。**

### 🎤 面试自述(2 分钟版)

```
我们做多 AI 接力写文章,自然想法是把协作信息挂在文章表上。但开局那一刻文章还不存在
——草稿要等第一棒真提交内容之后才创建。所以我建了一张独立的协作会话表,它先于文章存在。

有人问为什么不干脆提前把文章建出来、然后在列表里过滤掉空的。功能上确实也行,
但差别在于这个"空的不可见"的不变量由谁保证:提前建的话,它由每一个读路径保证
——今天两个页面,将来编辑器、搜索、通知、榜单、后台每加一个就多一个必须记得过滤的地方,
漏一个就漏出来。不建的话,它由数据的存在性保证,零个地方需要记得。

还有个更硬的理由:"零块草稿"这个过滤条件本身会失效——后面允许隐藏正文块之后,
会出现"有块但全隐藏"的合法草稿,按这个条件会被误伤。判据不稳比性能问题严重。

这跟我们项目里另外两个决定是同一个思路:模块边界不靠文档靠编译器,
Markdown 不靠"禁止 v-html"的纪律靠组件封装。能用结构约束的,就别只写在文档里。
```

### 📌 学习点

1. **建模顺序跟着业务时序走**——"开局"先于"有内容",所以载体也要先于内容表存在。
2. **判断两个方案时,问「这个不变量由谁承担」**,比问"哪个性能好"更接近本质。
3. **靠过滤条件维持的不变量是脆的**:每个新读路径都是一次遗漏机会,而且过滤条件本身会随需求演化失效。
4. **同一个判断反复出现时,它就是团队的设计价值观**,值得写进文档当判据用。

### 🔗 关联

- [故事 21:两个 AI 对话之间凭什么保证顺序](#故事-21两个-ai-对话之间没有共享内存凭什么保证它们写文章的顺序l15) —— 同一课的建模主线

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
