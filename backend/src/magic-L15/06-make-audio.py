# -*- coding: utf-8 -*-
"""
L15 ACPP Session、Ticket、Handoff · 方案B 音频生成脚本（生成期工具，运行期零依赖）。

用法（Windows，仓库根目录）：
    .venv-tts/Scripts/python backend/src/magic-L15/06-make-audio.py

产出（提交入库，HTML 直接引用）：
    06-assets/L15-session.mp3    一整条旁白音频（句间留 0.45s 呼吸）
    06-assets/timeline.js        每句的 [start, end] 秒级时间轴（供 HTML 同步字幕/高亮）

⚠ SENTENCES 必须与 06-L15ACPP接力-方案B-mp3.html 里 MODEL 的句子
  完全同文同序（脚本会校验句数）。改旁白 → 两处同步改 → 重跑本脚本。
依赖：venv 里的 edge-tts（微软在线 TTS，需联网）；系统 PATH 里的 ffmpeg/ffprobe。
"""
import asyncio
import json
import subprocess
import sys
from pathlib import Path

import edge_tts

VOICE = "zh-CN-YunjianNeural"   # 与前课一致音色
GAP_SECONDS = 0.45              # 句间停顿
ASSETS = Path(__file__).parent / "06-assets"
EXPECTED_COUNT = 41             # 与 HTML MODEL 句数对齐（防两边改漏）
# 分布：第一幕黑板 23 句（3+3+4+4+4+5）／第二幕代码 18 句（3+4+4+3+4）

# 按（步序, 句序）摊平的全部旁白。与 06 HTML 的 MODEL.steps[*].sentences 同文同序。
SENTENCES = [
    # ══════════ 第一幕 · 黑板：架构思考（不出代码，只画图） ══════════
    # step0 悬念
    "上一课，一个机娘学会了把开发过程写成草稿投进论坛。这一课的问题变了：怎么让两个机娘，接力写同一篇文章。",
    "听起来只是多一个人的事，其实完全不是。因为这两个机娘，跑在两个互不相通的 AI 对话里。",
    "先别急着想表怎么建。这一课我们从一个更根本的地方开始：先看清楚约束是什么。",

    # step1 约束
    "一个机娘在 Claude Code 里，另一个在 Codex 里。它们没有共享内存，读不到同一个文件，也不能互相发消息。",
    "它们之间唯一的通道，是你——你复制一串字符，从一个终端粘到另一个终端。",
    "记住这句话，这一课后面所有的设计，都是从这个约束推出来的。先认清约束，再谈方案。",

    # step2 设计空间
    "有了约束，我们来看有哪几种做法。第一种：主人提前排好队，说这篇文章先 A 写、再 B 写、最后 C 写。",
    "这要求你有先知——可现实是，你往往写完一段才决定要不要再叫一个人。预先排定，等于把动态的过程冻成了静态的计划。",
    "第二种：抢占式队列，谁想插就插。这个更不行——放弃了顺序，而且别人家的机娘也能插进来，多租户直接破防。",
    "第三种，也是我们选的：令牌接力。每入队一个人，就签发一张新的接力棒。谁拿到棒子，谁就能接下一棒。选它不是因为好看，是因为令牌是一串可以复制粘贴的字符——它天然匹配你刚才那个物理动作。",

    # step3 因果链
    "顺序怎么定？最偷懒的做法是打时间戳，谁先提交谁在前。这个做法会崩。",
    "因为时间戳表达的是观测：第二个机娘完全可能手比第一个快、机器时钟比它早、网络有时差，甚至有人手工改过系统时间。",
    "但有一件事是铁的：第二个机娘，只有拿到第一个交出的令牌之后，才可能入队。这个之后，是因果的，不是时钟的。",
    "所以我们把因果关系直接物化成一列外键，叫 predecessor ticket id，前一棒是谁。链表式追加，后来的必然在链尾。这就是分布式系统里说的因果序，Lamport 一九七八年那篇论文的核心：物理时钟不可信，因果链可信。",

    # step4 两张表
    "接下来一个容易被质疑的决定：席位和接力棒，为什么是两张表？它们看起来是一一对应的。",
    "第一个理由，状态空间本来就是二维的。席位的状态回答：已经进来的人能不能写。接力棒的状态回答：还能不能有新人进来。",
    "看这一格你就懂了：第一棒正在写字的时候，第二个机娘可以拿着尾棒入队。一个 LEASED，一个 AVAILABLE，两个维度同时在动，谁也决定不了谁。",
    "第二个理由更硬：基数在未来会破一比一。令牌过期之后的自愈动作是重新生成——同一棒之后会先后有好几张令牌。一行只能存一张，合表就装不下了。",

    # step5 互斥
    "最后一个问题，也是这一课最值钱的地方：同一根棒子，被两个人同时抢，怎么保证只有一个成功？",
    "最自然的写法是三步：查一下状态、判断能不能用、然后改成已消费。这个写法是错的。",
    "错在哪？两个线程会在同一时刻都读到可用，然后都判断通过，最后双双改成已消费——同一根棒子，产出了两个席位。这叫 TOCTOU，检查和使用之间有个空窗。",
    "正解是把判断塞进 WHERE：一条 UPDATE，条件里带上状态必须是可用。数据库在这一条语句内加行锁、求值、修改、放锁，中间没有任何缝隙。影响行数就是裁决书：一等于抢到，零等于没抢到。",
    "我们没有做任何魔法，只是把判断从 Java 搬进了 SQL，让语句这个天然的原子单位，替我们做互斥。黑板讲完了，下面看代码。",

    # ══════════ 第二幕 · 代码：真实行号走查 ══════════
    # step6 V012
    "先看建表。V012 建三张表：协作会话、贡献席位、接力棒。注意迁移编号是 V012 不是蓝图写的 V009——主线的 V009 早被邮箱登录占用了，照抄蓝图编号会让 Flyway 直接拒绝启动。",
    "会话表里，post id 和 draft id 都是可空的，因为草稿要等首棒真正提交内容之后才创建。这条来自一份主人签过字的产品规则：首棒失败，不暴露空草稿。",
    "还有一个细节值得看：会话指向链尾令牌，而令牌又指回会话，两张表互相引用成环。谁都不能先于对方建完外键，所以必须先建三张表，最后再用 ALTER 补上那条成环的外键。",

    # step7 XML
    "这就是刚才在黑板上说的那条原子消费。整块是一条 UPDATE，SET 里的逗号只是列清单分隔符，分号才是语句边界。",
    "判定条件全写在 WHERE 里，一个都不许挪到 Java：按摘要定位、状态必须可用、并且没有过期。",
    "特别看过期这一条。它写在 WHERE 里做惰性判定，意味着正确性完全不依赖清理任务是否及时——即使过期的令牌还挂着可用状态，也一定抢不到。这正是这一课能够不写任何后台任务，却依然安全的原因。",
    "还有一个坑藏在这里：这个时间比较，用的是应用传进来的时刻，不是数据库的 NOW 函数。为什么，等下讲。",

    # step8 Service
    "接力的服务方法一共五步，顺序是这一课我真正踩过坑的地方。",
    "第一步查令牌，只取上下文，不做任何判定。第二步才是闸门——那条原子消费，唯一的裁决者。过了闸门，这张令牌就独占归我了，后面每一步都没有竞争者。",
    "我最初把顺序写成了：查令牌、建票、再消费。结果是两个线程会双双先去建票，撞上席位的唯一键，输的那个拿到的是数据库的重复键异常，也就是五百错误，而不是干净的四百零九加上一句该去要新令牌的提示。",
    "这正是我刚在黑板上讲过的反面教材，我自己差点写出来。唯一键守的是另一个不变量，只是碰巧相关——靠它兜底，等于用异常控制业务流程。",

    # step9 并发测试
    "这条防线由并发测试守。两个线程和八个线程各跑一次，断言恰好一个赢家。为什么要加压到八个？因为两个线程有可能侥幸错开，看起来测过了其实没测到。",
    "最关键的是这一条断言：所有输家拿到的必须是干净的四百零九，而不是数据库约束异常。顺序一旦写回去，这里立刻变红。",
    "还有一个很容易写错的地方：这个测试类不能加事务注解。加了的话所有线程共享同一个事务和连接，根本形成不了竞争——测试会绿，但绿得毫无意义。",

    # step10 时区 + 收束
    "最后说那个时区的坑。有一条测试红了：过期的令牌居然被成功消费。我加了个探针直接问数据库，这令牌还有多久过期，配的是二十四小时，实际量出三十一。",
    "差整整八小时。根因是过期时间由 Java 写入，而 NOW 函数是数据库自己的墙上时间，两边的时区口径由 JDBC 连接参数决定——生产的连接串带了时区参数，测试用的容器连接串没带。",
    "修的时候我没有只是把参数补齐了事，而是改成用应用传入的时刻比较，让写入和比较走同一条转换路径。配置能救一次，结构能救一辈子。",
    "这一课三句话收尾：顺序不靠时间戳靠因果链；互斥不靠分布式锁靠一条带条件的 UPDATE；而蓝图自相矛盾的时候，靠的是主人签过字的那份产品规则。",
]


def ffprobe_duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(path)],
        capture_output=True, text=True, check=True)
    return float(out.stdout.strip())


async def synth_all(parts_dir: Path):
    sem = asyncio.Semaphore(6)   # 并发 6 路，礼貌调用在线服务

    async def one(i: int, text: str):
        target = parts_dir / f"part{i:03d}.mp3"
        async with sem:
            for attempt in range(3):             # 网络抖动重试
                try:
                    await edge_tts.Communicate(text, VOICE).save(str(target))
                    if target.stat().st_size > 500:
                        print(f"  [{i + 1:2d}/{len(SENTENCES)}] {target.name} OK")
                        return
                except Exception as e:
                    print(f"  [{i + 1:2d}] 第{attempt + 1}次失败: {e}")
                    await asyncio.sleep(2)
            raise RuntimeError(f"句 {i} 生成失败")

    await asyncio.gather(*(one(i, t) for i, t in enumerate(SENTENCES)))


def main():
    assert len(SENTENCES) == EXPECTED_COUNT, \
        f"句数 {len(SENTENCES)} ≠ 预期 {EXPECTED_COUNT}，先与 06 HTML 的 MODEL 对齐再跑"
    ASSETS.mkdir(exist_ok=True)
    parts = ASSETS / "parts"
    parts.mkdir(exist_ok=True)

    print(f"① edge-tts 合成 {len(SENTENCES)} 句（音色 {VOICE}）…")
    asyncio.run(synth_all(parts))

    print("② 生成句间静音 + 拼接…")
    silence = parts / "silence.mp3"   # 参数对齐 edge-tts 输出：24kHz 48kbps 单声道
    subprocess.run(
        ["ffmpeg", "-y", "-v", "quiet", "-f", "lavfi",
         "-i", "anullsrc=r=24000:cl=mono", "-t", str(GAP_SECONDS),
         "-c:a", "libmp3lame", "-b:a", "48k", "-ar", "24000", str(silence)],
        check=True)
    gap = ffprobe_duration(silence)

    timeline, cursor = [], 0.0
    concat_list = parts / "concat.txt"
    with concat_list.open("w", encoding="utf-8") as f:
        for i in range(len(SENTENCES)):
            p = parts / f"part{i:03d}.mp3"
            dur = ffprobe_duration(p)
            timeline.append([round(cursor, 3), round(cursor + dur, 3)])
            cursor += dur
            f.write(f"file '{p.as_posix()}'\n")
            if i < len(SENTENCES) - 1:
                f.write(f"file '{silence.as_posix()}'\n")
                cursor += gap

    final = ASSETS / "L15-session.mp3"
    subprocess.run(
        ["ffmpeg", "-y", "-v", "quiet", "-f", "concat", "-safe", "0",
         "-i", str(concat_list), "-c", "copy", str(final)],
        check=True)

    (ASSETS / "timeline.js").write_text(
        "// 由 06-make-audio.py 生成：每句在 L15-session.mp3 里的 [start, end] 秒。\n"
        "window.AUDIO_TIMELINE = " + json.dumps(timeline) + ";\n",
        encoding="utf-8")

    print(f"③ 完成：{final.name}  共 {cursor / 60:.1f} 分钟，{final.stat().st_size / 1024 / 1024:.2f} MB")
    print(f"   时间轴：timeline.js（{len(timeline)} 句）")
    print("   分句缓存在 06-assets/parts/（可删，重跑会重建）")


if __name__ == "__main__":
    sys.exit(main())
