# -*- coding: utf-8 -*-
"""
L14 单机娘 Skill 自动投稿 · 方案B 音频生成脚本（生成期工具，运行期零依赖）。

用法（Windows，仓库根目录）：
    .venv-tts/Scripts/python backend/src/magic-L14/06-make-audio.py

产出（提交入库，HTML 直接引用）：
    06-assets/L14-session.mp3    一整条旁白音频（句间留 0.45s 呼吸）
    06-assets/timeline.js        每句的 [start, end] 秒级时间轴（供 HTML 同步字幕/高亮）

⚠ SENTENCES 必须与 06-L14单机娘投稿-方案B-mp3.html 里 MODEL 的句子
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
EXPECTED_COUNT = 29             # 与 HTML MODEL 句数对齐（防两边改漏）

# 按（步序, 句序）摊平的全部旁白。与 06 HTML 的 MODEL.steps[*].sentences 同文同序。
SENTENCES = [
    # ===== 第一幕·黑板：架构思考（不出代码，只画图） =====
    # step0 悬念：机娘会认门却不会干活
    "上一课机娘拿到了自己的令牌，能通过 agent 斜杠 whoami 那道门报出自己是谁。但它还什么活都干不了——whoami 只是一块试金石。",
    "这一课给机娘第一个真本事：把一段开发过程写成草稿，投进论坛。但有一条铁律：机娘只能投草稿，绝不能自己发布。",
    "为什么？因为发布权永远属于人类主人。机娘写的东西，得主人审过、亲手点发布，才见得了人。这是整个产品的安全底线。",
    # step1 边界与考古：submit-single
    "动手前先破一个案。翻契约时我发现里面已经有一个机娘投稿接口，可它绑着一个叫 ticket 的席位，还带下一棒交接令牌——那是多机娘接力的东西。",
    "但这一课的验收线明明写着：不写多机娘接力。两边对不上。我没有照契约做，而是去查了三份更权威的设计文档。",
    "结论是：单机娘投稿本来就是一个独立用例，是活契约漏画了它、把它跟多机娘协作混成了一个。跟上一课挖出的 L10 缺席同一类账目问题——以权威蓝图为准，补画接口、登记漂移。",
    # step2 架构难题：身份跨模块读（DIP）
    "定了要新建投稿接口，马上撞上这一课唯一的真架构难题：机娘的身份，长在 identity 模块的私有角落里。",
    "可投稿这件事归 content 模块管。content 的控制器要读机娘是谁、用什么工具、哪次运行才能落库，但它不能直接伸手进 identity 的私有包去拿——那会被模块边界测试判红。",
    "这就是模块化的规矩：模块之间不许勾肩搭背地引用彼此的内部实现。那 content 到底怎么读到机娘身份？",
    "解法是依赖倒置：在人人都能依赖的 shared 公共区，定义一个只读接口，叫机娘身份，让 identity 那个机娘 principal 去实现它。content 只认接口，不碰 identity 的实现类。",
    "对比人类主人那侧：主人身份只是一个用户 id，塞进安全上下文的名字里就够了；机娘身份是一组字段，一个名字装不下，才需要一个接口当载体。对称，又有差异。",

    # ===== 第二幕·代码：真实行号走查 =====
    # step3 AgentIdentity 接口
    "进代码。先看这个新接口，放在 shared 的 security 包里。",
    "五个只读方法：机娘 id、背后的主人 id、设备 id、来源工具、运行 id。这就是一份跨模块的身份契约——谁都能依赖，但不暴露任何实现。",
    # step4 AgentPrincipal implements
    "identity 那侧几乎不用改。上一课的机娘 principal 本来就有这五个字段，这一课只在末尾加一句：实现那个接口。",
    "record 会自动把这五个组件变成接口要的五个方法。零逻辑改动，一句话就把 identity 的实现和 shared 的契约接上了。",
    # step5 ContentService 复用内核
    "投稿的落库逻辑在 content 服务里。主人投稿和机娘投稿，八成的活是一样的：建帖子指针、建不可变贡献、建草稿、建正文块。",
    "所以我把这套公共动作抽成一个私有内核方法，唯一的差别——谁写的——用一个作者维度对象传进去。",
    "机娘投稿这个方法因此很薄：从令牌身份拼出机娘版作者维度，调公共内核就完事。主人那个方法同样薄，重复代码一行都没有。",
    # step5b DraftAuthor record
    "作者维度就是这么个小 record，两个静态工厂：主人版填用户 id、机娘那几列留空；机娘版填机娘 id、带上工具和运行 id，归属键填机娘背后的主人。",
    # step6 AgentDraftController
    "接口这一层。新建的机娘草稿控制器，挂在 agent 斜杠 drafts 上，走的是上一课那条只认机娘令牌的第三条链。",
    "注意它读身份用的是 shared 的接口，不是 identity 的实现类——这就是刚才那个依赖倒置，落到代码上的样子。",
    "整个控制器只有一个建草稿的动作，从头到尾没有发布接口。机娘无 publish 不是忘写，是刻意的验收线。投完拼一个草稿地址回给主人去审。",
    # step7 SubmitCommand CLI
    "命令行这侧的 submit 命令。前面它取出本地存的机娘令牌、读了正文文件，现在带着令牌调投稿接口。",
    "中间几段是错误处理：令牌过期提示重新 assume，其它错误把服务端的 code 透传出来。",
    "成功后，标准输出只吐一段机器可读的 JSON，含草稿地址，供 skill 回给主人。",
    "而令牌从头到尾绝不打印。标准输出里只有草稿信息，这条铁律从上一课贯穿到现在。",

    # ===== 收尾 =====
    # step8 skill + 小抄 + 面试
    "把这些串起来的是一个 skill：一个指令文件夹，告诉 AI 工具先确认登录、列机娘、代入、写贡献、投稿、把草稿地址回给主人、提醒他审稿。多机娘接力那段我裁掉了，那是下一课。",
    "一张小抄。机娘投稿镜像主人投稿：靠 URL 前缀分链、靠公共内核复用、靠一个 shared 接口跨模块读身份。三个动作，一条铁律——机娘只投草稿，不能发布。",
    "面试被问怎么让一个 AI 智能体安全地往你系统里写数据，就答这一套：独立认证链、身份走接口跨模块解耦、写权限收窄到只能建草稿、发布权留给人。比空谈 AI 安全扎实得多。",
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

    final = ASSETS / "L14-session.mp3"
    subprocess.run(
        ["ffmpeg", "-y", "-v", "quiet", "-f", "concat", "-safe", "0",
         "-i", str(concat_list), "-c", "copy", str(final)],
        check=True)

    (ASSETS / "timeline.js").write_text(
        "// 由 06-make-audio.py 生成：每句在 L14-session.mp3 里的 [start, end] 秒。\n"
        "window.AUDIO_TIMELINE = " + json.dumps(timeline) + ";\n",
        encoding="utf-8")

    print(f"③ 完成：{final.name}  共 {cursor / 60:.1f} 分钟，{final.stat().st_size / 1024 / 1024:.2f} MB")
    print(f"   时间轴：timeline.js（{len(timeline)} 句）")
    print("   分句缓存在 06-assets/parts/（可删，重跑会重建）")


if __name__ == "__main__":
    sys.exit(main())
