# -*- coding: utf-8 -*-
"""
L12 CLI 与浏览器设备配对 · 方案B 音频生成脚本（生成期工具，运行期零依赖）。

用法（Windows，仓库根目录）：
    .venv-tts/Scripts/python backend/src/magic-L12/06-make-audio.py

产出（提交入库，HTML 直接引用）：
    06-assets/L12-session.mp3    一整条旁白音频（句间留 0.45s 呼吸）
    06-assets/timeline.js        每句的 [start, end] 秒级时间轴（供 HTML 同步字幕/高亮）

⚠ SENTENCES 必须与 06-L12会话与安全内幕-方案B-mp3.html 里 MODEL 的句子
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
EXPECTED_COUNT = 38             # 与 HTML MODEL 句数对齐（防两边改漏）

# 按（步序, 句序）摊平的全部旁白。与 06 HTML 的 MODEL.steps[*].sentences 同文同序。
SENTENCES = [
    # step0 三方握手大图
    "本课开场，AgentLog 进入新阶段：让没有浏览器的命令行工具，也能安全登录，用的是业界标准 OAuth 设备授权流。",
    "整个流程是三方握手：CLI 发起配对，后端发两个码；你在浏览器批准；CLI 再来换令牌。左边这张图就是全程。",
    "关键在这两个码。deviceCode 是长的，给机器私藏轮询用；userCode 是短的，给你在浏览器手输。",
    "为什么要拆两个？能安全轮询的通道，和人能手输的通道，安全属性不一样——短码不能当长期凭证，长码人打不动。这就是设备流的精髓。",
    # step1 createPairing 发起配对
    "第一步，CLI 发起配对，走这个 createPairing。它匿名调用，因为 CLI 此刻还没有任何令牌。",
    "九十四行生成 deviceCode，九十五行生成 userCode，一个给机器，一个给人，正是刚才说的两个码。",
    "九十九行是安全的第一个要点：库里存的不是 deviceCode 明文，是它的摘要；明文只在响应里回给 CLI 这一次。",
    "同一台设备重复配对不会重复建安装记录，认 installationCode 复用；返回里还带上 verificationUri，就是让 CLI 打印给你的那个批准网址。",
    # step2 TokenService 令牌安全内幕
    "这个摘要怎么算的？看令牌基建 TokenService。三十九行生成明文令牌：前缀加三十二个随机字节的 base64。",
    "四十六行的 digest 才是关键：用 HMAC-SHA256，拿服务端的 pepper 当密钥，把明文令牌算成三十二字节摘要，库里只存这个摘要。",
    "为什么不存明文？万一脱库，攻击者拿到的只是摘要，没有 pepper 算不出明文，冒用不了。跟密码存 bcrypt 一个道理，可验证、不可逆推。",
    "为什么用 HMAC 不用纯哈希？HMAC 带了 pepper 这把密钥，挡住彩虹表预计算；pepper 走环境变量，绝不进 git。这是 CLI 和机娘双轨认证的地基。",
    # step3 V010 表结构
    "落到表结构。device_pairing_request 这张表，三十五行 device_code_digest 是 BINARY 三十二，正好装下那个摘要。",
    "三十六行 user_code 反而是明文存的：它短、低敏感、十分钟就作废，不值得加密。两个码的存法不同，是按敏感度和寿命定的。",
    "四十三行给 user_code 加了唯一键：保证你在浏览器输入的短码，能唯一定位到一条配对请求。",
    # step4 安全配置 匿名放行
    "既然 CLI 端点匿名，安全配置就得放行。六十五行把发起配对和轮询这两个端点加进 permitAll，免登录。",
    "同时它们还得豁免 CSRF——CLI 没有浏览器 Cookie，拿不到 CSRF 令牌。这两处一起改，缺一个都调不通。",
    "但注意，只放行这两个 CLI 端点；浏览器确认那个端点绝不放行，它照旧要登录加 CSRF。这就是双轨认证的第一次照面。",
    # step5 WebController 确认端点
    "第二步，你在浏览器批准，走这个 confirm 端点，它需要登录态。",
    "三十四行是防越权的命门：currentUserId 从登录 Session 里取，绝不让前端传，否则攻击者能把别人的配对确认到自己名下。",
    "顺带说，这个确认端点原来的契约里没有——设备流缺了它跑不通，所以我们新增了它，并登记成冻结面漂移 D 零八。",
    # step6 confirmPairing 确认逻辑
    "确认的逻辑在 confirmPairing：按你输入的 userCode 查出配对请求，然后开始检查。",
    "一百二十行先看过期：超过十分钟就置 EXPIRED 打回，这是验收项过期处理的一半。",
    "一百二十九行把状态推进到 CONFIRMED，记下是哪个主人批准的。",
    "一百三十七行同时把这台设备绑定到你、状态转 ACTIVE。到这，设备就认你当主人了。",
    # step7 exchangePairing 换令牌
    "第三步，CLI 轮询换令牌，走 exchangePairing，它按 deviceCode 的摘要查配对。",
    "一百五十四行，过期就返回 EXPIRED；还没批准就返回 PENDING，让 CLI 继续轮询。注意这俩都是正常轮询结果，不是报错。",
    "已确认了，一百六十七、一百六十八行签发两个令牌：owner access 和 refresh，明文照例只回这一次。",
    "一百八十五行是防重放的命门：换完令牌立刻把配对置 CONSUMED，同一个 deviceCode 第二次来换直接拒。",
    "为什么要一次性？不然截获了 deviceCode 的人，能反复换出很多有效令牌。一次性兑换券，用完即废。",
    # step8 PairingStatus 状态机
    "这四个状态就是配对的一生：PENDING 等批准，CONFIRMED 已批准，CONSUMED 已换过令牌，EXPIRED 超时。",
    "把它们做成常量字典，而不是散落的字符串，是本项目的一贯做法——状态值有唯一的事实源，改一处就够。",
    # step9 双轨认证大图
    "退一步看全局。同样是配对，浏览器端点走 Session 加 CSRF，CLI 端点匿名换 opaque 令牌，这就是双轨认证。",
    "按客户端类型分轨：浏览器有 Cookie 用 Session，CLI 无头用 Bearer 令牌。这是 L05 就埋下的设计。",
    "不过本课签发的 owner 令牌，还没有过滤器去消费它。铸令牌是本课，用令牌的 Bearer 过滤器和独立 CLI 认证链，是下一课 L13。每课单一职责。",
    # step10 小抄+面试
    "收尾，一张小抄。设备流三步：发起配对拿两码，浏览器输短码批准，CLI 凭长码换令牌。",
    "三个安全支点记牢：明文只回一次，库存 HMAC 摘要，deviceCode 一次性防重放。",
    "面试被问怎么让 CLI 登录，就答 OAuth 设备授权流，说清 deviceCode 和 userCode 为什么分家——这比背概念扎实得多。",
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

    final = ASSETS / "L12-session.mp3"
    subprocess.run(
        ["ffmpeg", "-y", "-v", "quiet", "-f", "concat", "-safe", "0",
         "-i", str(concat_list), "-c", "copy", str(final)],
        check=True)

    (ASSETS / "timeline.js").write_text(
        "// 由 06-make-audio.py 生成：每句在 L12-session.mp3 里的 [start, end] 秒。\n"
        "window.AUDIO_TIMELINE = " + json.dumps(timeline) + ";\n",
        encoding="utf-8")

    print(f"③ 完成：{final.name}  共 {cursor / 60:.1f} 分钟，{final.stat().st_size / 1024 / 1024:.2f} MB")
    print(f"   时间轴：timeline.js（{len(timeline)} 句）")
    print("   分句缓存在 06-assets/parts/（可删，重跑会重建）")


if __name__ == "__main__":
    sys.exit(main())
