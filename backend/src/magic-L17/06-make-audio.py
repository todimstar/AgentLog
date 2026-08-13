# -*- coding: utf-8 -*-
"""
L17 Worker、Audit、Modulith 与 Redis · 方案B 音频生成脚本（生成期工具，运行期零依赖）。

用法（Windows，仓库根目录）：
    .venv-tts/Scripts/python backend/src/magic-L17/06-make-audio.py

产出（提交入库，HTML 直接引用）：
    06-assets/L17-worker.mp3     一整条旁白音频（句间留 0.45s 呼吸）
    06-assets/timeline.js        每句的 [start, end] 秒级时间轴（供 HTML 同步字幕/高亮）

⚠ SENTENCES 与 06-L17Worker与秩序自愈-方案B-mp3.html 里 MODEL 的句子【同文同序】——
  本文件是【从 HTML 自动提取生成】的，不要手改；
  改旁白请改 HTML 的 MODEL，然后重跑 .build/gen-audio-script.js 重新生成本文件。
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
EXPECTED_COUNT = 55            # 与 HTML MODEL 句数对齐（防两边改漏）
# 分布：第一幕黑板 34 句（3+4+4+4+5+4+5+5）／第二幕代码 21 句（4+3+4+5+5）

# 按（步序, 句序）摊平的全部旁白。与 06 HTML 的 MODEL.steps[*].sentences 同文同序。
SENTENCES = [
    "上一课我们反复说过一句话：状态不准，不等于行为不对。当时是为了说明，不写后台任务也是安全的。",
    "这一课要补上那句话的后半截——状态不准，是会骗人的。",
    "机娘领了租约之后对话崩了，十五分钟过去，租约在逻辑上早就失效了。但数据库里，尝试还挂着进行中，席位还挂着已租用，尾令牌还挂着可用。没有任何人去改它们。",
    "这个谎言会骗到三个人。你打开时间线页，看到第二棒正在写，其实它已经死了十五分钟。",
    "排在第三棒的机娘B，看到前一棒还没完成，于是继续等——但它永远等不到了。",
    "机娘A 换个新对话回来，想问我那一棒怎么了，没有任何地方能告诉它。",
    "所以这一课要回答的就是一句话：一棒死了之后，谁去宣布它死了、告诉后面的人别等了、并留下一份可查的记录？",
    "到目前为止，这个项目里所有的代码都是被请求触发的。浏览器点一下，或者命令行敲一条命令，然后控制器、服务、数据库。",
    "但是宣布一棒死了这件事，没有人会来请求。机娘的对话已经崩了，它不会回来说我死了；你也不该需要手动去点一下。",
    "所以需要一个新东西：自己醒来、自己找活干、干完继续睡。这就是 Worker，后台工作者。",
    "它每三十秒醒一次，扫一遍数据库，找那些状态还是进行中、但租约时间已经过去的记录。有就处理，没有就继续睡。",
    "上线之后为了不宕机，同一个服务通常会跑两份。于是问题来了：两个实例的 Worker 同时醒过来，都扫到同一批过期记录，都去处理。",
    "结果就是同一棒被宣布死亡两次，写出两条事故报告、两条审计记录。硬验收里那条多 Worker 不重复，说的就是这件事。",
    "有三种办法。只让一个实例跑，那个实例挂了就没人干活；用 Redis 分布式锁，那是拿外部依赖当正确性防线，我们的设计规范明确禁止；",
    "第三种，让数据库自己来分活。我们选它。",
    "先说 FOR UPDATE，它的意思是：我选出来的这些行，先锁住，别人不许动。",
    "为什么 Worker 要用它，而不是前两课那种一条带条件的 UPDATE？因为处理一条过期记录要做七八件事——改尝试、改席位、改后序、冻结令牌、写事故报告、发事件，塞不进一条 SQL。所以必须先把这一批占住，再慢慢做。",
    "关键是后面那个 SKIP LOCKED：已经被别人锁住的行，我不等，直接跳过去拿下一批。",
    "实例一拿到前五十条锁住，实例二发现被锁了就跳过，去拿第五十一到一百条。如果没有这个 SKIP LOCKED，实例二会排队等实例一处理完，两个 Worker 就变成串行，白养一个。",
    "一句话：SKIP LOCKED 把数据库的行，变成了一个天然的任务队列。不需要 Redis，不需要消息队列，不需要选主。",
    "拿到五十条活儿之后，怎么处理？最直觉的写法是一个大事务包住它们。这个写法有个致命问题。",
    "假设第二十三条数据有问题抛了异常，整批回滚，前面二十二条的成果全没了。三十秒后 Worker 又醒来，又扫到同样这五十条，又在第二十三条炸，又全回滚。",
    "结果是永久卡死，那四十九条正常的记录永远处理不了。这个故障模式有个名字，叫毒丸消息——一条坏数据毒死整个队列。",
    "解法是每条一个独立的小事务，外层用 try catch 兜住，坏的那条记日志跳过，其余照常完成。",
    "一棒死了之后，要动五样东西。尝试和席位各改一条，后序的票批量阻塞，尾令牌冻结，最后是会话状态。",
    "注意第三项和第四项，它们是两个正交的维度，正是 L15 把票和接力棒分成两张表的原因。",
    "票的阻塞，管的是已经进来的人能不能写，有几棒就改几张；尾令牌的冻结，管的是还能不能有新人进来，永远只有悬空的那一根。",
    "为什么后序也要拦住？因为第二棒缺失了，文章会变成第一棒直接跳到第三棒，中间断一块。而第三棒的机娘是读着前面的内容往下写的，它会基于一篇残缺的文章续写。",
    "还有一个分支要注意：如果死的是首棒，会话直接作废。因为首棒失败意味着草稿从来没被创建过，整局没有任何东西可救。中间棒失败才是保留草稿、等主人重试的场景。",
    "一棒死了之后，还有两件事要做：写事故报告、写审计流水。将来还要通知你。但这两件事都不属于协作模块。",
    "如果让 Worker 直接调它们，它会越来越胖，而且每加一个订阅者就得改它一次。",
    "用事件之后，Worker 只喊一声——我这里有一棒超时了。谁关心谁自己订阅，加第四个订阅者时 Worker 一个字都不用改。",
    "Spring Modulith 比普通事件多给了一样东西。普通事件的监听器执行失败，事件就丢了；Modulith 会在业务的同一个事务里，把事件写进一张表，监听器成功就标记完成，失败就留着，重启后重投。",
    "这个模式叫事务性发件箱，是分布式系统里的经典解法，面试很爱问。但要记住它的边界：事件只做派生行为，不变量永远由数据库状态机守。因为事件是异步的，异步的东西不能用来守不变量。",
    "这就是刚才在黑板上说的那条扫描语句。三个细节值得停下来看。",
    "第一，时间比较用的是应用传进来的时刻，不是数据库的 NOW 函数。这是上一课血的教训——两者的时区口径由连接参数决定，实测差八小时。",
    "第二，这里比上一课更危险。上一课判错了，测试会立刻变红；这里判错了，只是少捞或者多捞几条，测试静默通过，没有人会发现。所以我专门写了一条探针测试：造一个刚过期一秒的记录，断言 Worker 能捞到。时区一错，这条立刻红。",
    "第三，按到期时间排序，先处理死得最久的，避免有记录永远排不上队。",
    "Worker 本身非常薄，只做两件事：拿活，分发。一行业务逻辑都没有。",
    "高亮的这段 try catch 就是毒丸防线。它和每条一个独立事务是一对——独立事务保证坏的那条回滚不会影响别人，try catch 保证循环不会中断。",
    "还有一个细节：扫描间隔写成了配置项，默认三十秒。因为它是体验参数不是安全参数，运维可以随时调。",
    "这是宣告死亡的入口。你在设计评审的时候质疑过第一行那道闸门：既然这些编号都是从超时记录里找出来的，就算机娘之后提交了，也不该对草稿造成任何改变吧？",
    "你说得对。两个条件是互斥的：提交要求租约还没过期，而 Worker 捞的正是已经过期的。机娘已经提交不进来了。",
    "但这道闸门防的不是机娘，是另一个 Worker。因为第一步那个 FOR UPDATE 的锁，在那个事务结束的时候就放掉了。等到这个方法开始执行，那条记录可能已经被别的实例处理完了。",
    "而且它必须放在七步的最前面。如果放到第四步才检查，前面三步已经改了状态，闸门返回零的时候，那些改动收不回来。",
    "这一站讲一个我做错、被机器纠正的地方。",
    "我在施工蓝图里，把那七步状态推进放在了可靠性模块里。结果模块边界测试一次跑出十二条违规——因为 Worker 要改席位和会话的状态，就得引用协作模块的私有类。",
    "修法和上一课的内容门面完全一样：在协作模块的根包开一个门面接口，实现类留在私有子包里。",
    "修完之后我才想清楚划分的判据：什么时候做，归可靠性模块；做什么，归协作模块。那七步全都是协作协议自己的状态机推进，是协作模块的领域知识；可靠性模块的职责只是触发时机。",
    "这个测试是第二课建的，它的价值在这里兑现了：边界不靠自觉，靠机器强制。人在赶工的时候，一定会顺手引用。",
    "最后看限流。令牌桶的算法是：桶里的令牌按时间自动补充，每来一个请求拿走一个，桶空了就拒绝。",
    "为什么要写成 Lua 脚本？因为算令牌要四步——读当前值、算这段时间补了多少、判断够不够、写回新值。这四条命令分开发过去，中间会被别的请求插进来，又是检查和使用之间的空窗。Redis 执行脚本是原子的，四步变一步。",
    "认出来了吗，这是同一个思想的第三种形态。改已有的行，用条件更新；建全新的行，用抢占插入；到了 Redis 这边，用脚本。三种实现，一个道理：让检查和动作，在一个不可分割的单位里完成。",
    "最后一个问题：为什么限流可以放 Redis，而前两课那些闸门必须留在数据库？因为限流不是不变量。少限几次多限几次，不会导致数据错乱；而谁抢到令牌、谁抢到租约，是真正的不变量，错一次就出脏数据。",
    "这一课就到这里。下一课我们做重试和终止——那属于另一条链：人的决策链。",
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

    final = ASSETS / "L17-worker.mp3"
    subprocess.run(
        ["ffmpeg", "-y", "-v", "quiet", "-f", "concat", "-safe", "0",
         "-i", str(concat_list), "-c", "copy", str(final)],
        check=True)

    (ASSETS / "timeline.js").write_text(
        "// 由 06-make-audio.py 生成：每句在 L17-worker.mp3 里的 [start, end] 秒。\n"
        "window.AUDIO_TIMELINE = " + json.dumps(timeline) + ";\n",
        encoding="utf-8")

    print(f"③ 完成：{final.name}  共 {cursor / 60:.1f} 分钟，{final.stat().st_size / 1024 / 1024:.2f} MB")
    print(f"   时间轴：timeline.js（{len(timeline)} 句）")
    print("   分句缓存在 06-assets/parts/（可删，重跑会重建）")


if __name__ == "__main__":
    sys.exit(main())
