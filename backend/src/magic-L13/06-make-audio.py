# -*- coding: utf-8 -*-
"""
L13 令牌消费 · 三条安全链与机娘登场 · 方案B 音频生成脚本（生成期工具，运行期零依赖）。

用法（Windows，仓库根目录）：
    .venv-tts/Scripts/python backend/src/magic-L13/06-make-audio.py

产出（提交入库，HTML 直接引用）：
    06-assets/L13-session.mp3    一整条旁白音频（句间留 0.45s 呼吸）
    06-assets/timeline.js        每句的 [start, end] 秒级时间轴（供 HTML 同步字幕/高亮）

⚠ SENTENCES 必须与 06-L13三条安全链与机娘登场-方案B-mp3.html 里 MODEL 的句子
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
EXPECTED_COUNT = 36             # 与 HTML MODEL 句数对齐（防两边改漏）

# 按（步序, 句序）摊平的全部旁白。与 06 HTML 的 MODEL.steps[*].sentences 同文同序。
SENTENCES = [
    # ===== 第一幕·黑板：架构思考（不出代码，只画图） =====
    # step0 401 悬念
    "上一课我们给 CLI 铸出了令牌，存进了库。这一课先抛一个悬念：CLI 拿着一枚真令牌，去敲业务接口，却被拒了个 401。为什么？",
    "因为到现在为止，整个后端只有一条安全链，它认的是浏览器的 Session Cookie。CLI 没有 Cookie，只有一枚 Bearer 令牌。",
    "这条链一看你没有 Session，直接判你未登录。令牌是真的，可根本没有一段代码去读它、认它。这就是这一课要填的坑。",
    # step1 一条链→三条链
    "那能不能在这条链里顺手也认一下 Bearer 令牌？不行。浏览器要有状态、要开 CSRF；令牌要无状态、要关 CSRF。同一条链没法两套都要。",
    "正解是分链：一条后端，按 URL 前缀分给不同的安全链，各自一套配置。左边这张图，从一条链长出三条。",
    "Chain 1 管 web 斜杠，走 Session；Chain 2 管 cli 斜杠，认 owner 令牌；Chain 3 管 agent 斜杠，认机娘令牌。",
    "谁接管一个请求，只看 URL、不看令牌，而且在碰令牌之前就定了。Spring 按 Order 从小到大问每条链：这个 URL 匹配你吗？第一个说匹配的独占接管。",
    # step2 四层模型·机娘登场
    "为什么要第三条链？因为这一课的主角登场了：机娘。前两条链的令牌都代表你本人，第三条代表一个被你代入的机娘身份。",
    "这就是四层模型：设备安装、配对票据、主人令牌，最后第四层，机娘代理会话。前三层上一课备齐，第四层这一课才真正出现。",
    "机娘其实早就存在，它是论坛里会发帖、有粉丝、有人格的社交身份。这一课给这个社交身份配一把运行时的钥匙，让它能自己登录干活。",

    # ===== 第二幕·代码：真实行号走查 =====
    # step3 OwnerBearerAuthenticationFilter 验币
    "进代码。Chain 2 的核心是这个验币过滤器。五十五行读 Authorization 头，没有 Bearer 就放行，交给下游返通用 401。",
    "六十二、六十三行是闭环的关键：拿客户端传来的明文令牌，用上一课铸币那个同一个 digest 方法算摘要，再拿摘要查 owner 会话表。",
    "六十六行，查不到或状态不是 ACTIVE，判无效，让你重新配对；七十一行，过期了，判过期，让你去 refresh 换新。两种失败语义分开。",
    "七十六到七十九行，验过了，造一个 owner 身份塞进安全上下文，这次请求就算认证通过。上一课铸币、这一课验币，钱终于花出去了。",
    # step4 CliSecurityConfiguration 多链
    "这条链怎么装配？二十九行 Order 一，三十七行 securityMatcher 只管 cli 斜杠。",
    "三十八、三十九行跟 Chain 1 处处相反：关掉 CSRF、设成无状态。因为 Bearer 令牌不走 Cookie，没有 CSRF 面，也不需要会话。",
    "四十二到四十四行留了三个匿名端点：配对两个、加 refresh 一个。道理是换令牌的过程本身不能要令牌，这是个先有鸡蛋的问题。",
    "注意三十四行，过滤器是 new 出来的，不是注入的 Bean。这里藏着一个坑，等下专门讲。四十八行把它插进这条链的认证位。",
    # step5 OwnerSessionService refresh RTR
    "令牌只活一小时，过期怎么办？总不能每小时让你重新扫码。拿那枚三十天的 refresh 令牌来换一套新的。看 refresh 方法。",
    "这一课选的是全轮换：每次刷新，不只发新的 access，连 refresh 也一起换新，把旧会话作废。这是 OAuth 安全最佳实践。",
    "七十二到七十四行就是轮换：旧会话置成 REVOKED，旧的 access 因此连带立即失效；八十一行往下插一条全新的会话。",
    "最精彩的是六十行这个分支：如果一枚已经被轮换掉、本该作废的 refresh 又冒出来用，说明它被复制了、泄漏了。",
    "六十一行的反应很决绝：连坐吊销这台设备名下所有会话，逼你重新配对。用旧令牌的重放，反而成了系统发现盗用的信号。",
    # step6 AgentAssumeService assume
    "机娘怎么登场？owner 用自己的令牌，代入自己名下的一个机娘，换一把窄的机娘令牌。看 assume 方法。",
    "四十九、五十行查出这个机娘的主人和状态。五十六行是整个代入的命根子：只能代入属于你自己、且在启用状态的机娘，否则一律返回 404。",
    "为什么是 404 不是 403？因为连别人机娘的存在性都不该泄漏给你。这是一个很细的安全考量。",
    "六十一行往下铸一把机娘令牌，按 agent、source_tool、client_run_id 记一条会话。每一次运行都是独立的一把，互不干扰。",
    # step7 V011 迁移
    "落到表结构。V011 建的这张 agent_acting_session，就是四层模型的第四层。",
    "二十六行的隔离键是设计的精华：agent 加工具加运行 id 三者组合。这就回答了你多机娘怎么隔离的问题——细到每一次运行。",
    "二十八到三十行三个外键，把机娘会话钉死在真实的机娘、设备、主人上。迁移编号 V011，是接着 V010 顺延的，不是照抄蓝图，否则会撞车。",
    # step8 双重注册踩坑
    "回头讲那个坑，这是这一课最真实的一课。加完第三条链，测试突然红了：带 owner 令牌的 cli 请求，被判了 401。",
    "根因是 Spring Boot 的一个陷阱：一个过滤器如果既继承 OncePerRequestFilter、又标了 Component，它会被注册两次，其中一次是全局对所有 URL 生效。",
    "于是机娘过滤器全局拦截了 cli 请求，拿 owner 令牌去查机娘表，查不到就 401。修法就是去掉 Component、改用 new 构造，把作用域死死锁在自己那条链里。",

    # ===== 收尾 =====
    # step9 小抄+面试
    "收尾，一张小抄。三条链按 URL 分：web 走 Session、cli 认 owner 令牌、agent 认机娘令牌，选链只看 URL 不看令牌。",
    "两个安全招法记牢：refresh 全轮换加盗用连坐、assume 只能代入自己名下的机娘。还有一个工程教训：链专用的过滤器别设成 Component。",
    "面试被问怎么给命令行工具和 AI 智能体做认证，就答多条安全链加不透明令牌，讲清 assume 式的权限代入——这比背概念扎实得多。",
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

    final = ASSETS / "L13-session.mp3"
    subprocess.run(
        ["ffmpeg", "-y", "-v", "quiet", "-f", "concat", "-safe", "0",
         "-i", str(concat_list), "-c", "copy", str(final)],
        check=True)

    (ASSETS / "timeline.js").write_text(
        "// 由 06-make-audio.py 生成：每句在 L13-session.mp3 里的 [start, end] 秒。\n"
        "window.AUDIO_TIMELINE = " + json.dumps(timeline) + ";\n",
        encoding="utf-8")

    print(f"③ 完成：{final.name}  共 {cursor / 60:.1f} 分钟，{final.stat().st_size / 1024 / 1024:.2f} MB")
    print(f"   时间轴：timeline.js（{len(timeline)} 句）")
    print("   分句缓存在 06-assets/parts/（可删，重跑会重建）")


if __name__ == "__main__":
    sys.exit(main())
