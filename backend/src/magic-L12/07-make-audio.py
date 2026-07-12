# -*- coding: utf-8 -*-
"""
L12 CLI 命令讲解 · 方案B 音频生成脚本（生成期工具，运行期零依赖）。

用法（Windows，仓库根目录）：
    .venv-tts/Scripts/python backend/src/magic-L12/07-make-audio.py

产出（提交入库，HTML 直接引用）：
    07-assets/L12-cli-session.mp3   一整条旁白音频（句间留 0.45s 呼吸）
    07-assets/timeline.js           每句的 [start, end] 秒级时间轴

⚠ SENTENCES 必须与 07-L12-CLI命令讲解-方案B-mp3.html 里 MODEL 的句子完全同文同序（脚本校验句数）。
依赖：venv 里的 edge-tts（需联网）；系统 PATH 里的 ffmpeg/ffprobe。
"""
import asyncio
import json
import subprocess
import sys
from pathlib import Path

import edge_tts

VOICE = "zh-CN-YunjianNeural"
GAP_SECONDS = 0.45
ASSETS = Path(__file__).parent / "07-assets"
EXPECTED_COUNT = 35

SENTENCES = [
    # step0 CLI 定位 + 一手终端一手浏览器
    "上一个视频讲后端怎么发码验令牌，这个视频讲另一头——CLI，也就是命令行工具，它到底是什么、怎么跑。",
    "先破一个最容易懵的点：CLI 是个终端程序，不在浏览器里，也不在前端网页里。它是 AI 工具的手，被动执行命令。",
    "配对时是一手终端、一手浏览器：终端跑 auth login 拿到配对码，浏览器打开批准页输码点批准，终端那头自动完成。",
    "为什么这么别扭？因为 CLI 没有浏览器，登录得借你另一块屏幕上已登录的浏览器——这正是设备授权流的形态。",
    # step1 AgentLogCli 入口
    "先看入口。AgentLogCli 用 picocli 框架，十六行一个 Command 注解，就把这个类变成了命令 agentlog。",
    "二十行注册子命令 AuthCommand——所以你能敲 agentlog auth login。本课只挂 auth，后面 L13 加 agent assume。",
    "三十行 main 里 execute 一跑，picocli 自动解析参数、找到对应子命令、调用它，你不用手写参数解析。",
    # step2 Login 发起配对
    "auth login 的正主在这。四十一行读本地配置，四十五行拿后端地址，四十六行拿或生成本机的 installationCode。",
    "installationCode 是这台 CLI 的设备身份，第一次跑随机生成、存本地，以后每次配对都用它——同机多个 CLI 靠它区分。",
    "五十行拼请求体，五十三行 POST 发起配对。这一步匿名，CLI 手上还没任何令牌。",
    "五十八到六十行，从响应里取出后端发的 deviceCode、userCode、还有批准页地址 verificationUri。",
    # step3 提示 + 轮询
    "六十六六十七行，把批准网址和配对码打印到屏幕——注意走的是标准错误 stderr，等会讲为什么。",
    "七十三行开始轮询循环：每隔几秒问一次后端，直到拿到结果或超时。这就是设备流里 CLI 那个不停问的动作。",
    "七十四行先睡 pollInterval 秒，七十五行 POST 轮询端点，把 deviceCode 递过去问：批准了没？",
    "八十一行读回来的 status：APPROVED 批了、EXPIRED 过期了、PENDING 还没批，三条岔路。",
    # step4 三条岔路
    "八十二行批准了：八十五行把后端发的两个令牌存进本地，然后返回成功。注意——令牌只存文件，绝不打印到屏幕。",
    "一百零一行是 PENDING：还没批，打个点继续轮询。你在终端看到的那排小点，就是它。",
    "九十六行 EXPIRED：配对码过了十分钟，提示重来。这三条岔路，就是轮询的全部逻辑。",
    # step5 ApiClient
    "刚才那些 postJson，实现在 ApiClient。它用 JDK 自带的 HttpClient，不依赖 Spring——CLI 是独立轻量程序。",
    "三十一到三十六行就是标准的发 POST、收响应、解析 JSON，三十九行包成一个带状态码的 Result。",
    "为什么不用后端那套生成的客户端？CLI 只调三两个接口，手写最小 HTTP 更轻、启动更快。",
    # step6 CliConfig
    "本地存储分两个文件，先看非密的 config.json，管它的是 CliConfig，重点是这个 installationCode。",
    "五十五行先看本地有没有存过，有就直接返回——保证同一台 CLI 每次都是同一个身份。",
    "没有就五十八行随机生成一个、存下来。这就是我说的：同机跑多个 CLI，各自一个 installationCode，天然隔离。",
    # step7 CliPaths AGENTLOG_HOME
    "那多个 CLI 的配置怎么放到不同地方？看 CliPaths。二十三行先看环境变量 AGENTLOG_HOME。",
    "设了就用你指定的目录，没设就用默认的用户目录下 .agentlog。每个 skill 设一个不同的 AGENTLOG_HOME，配置和凭据就各存各的、互不干扰。",
    # step8 CredentialStore
    "密的那半在 CredentialStore，credentials.json。三十三到三十五行把两个令牌和过期时间写进去。",
    "关键一条铁律：令牌只写文件，绝不打印到控制台或日志。你翻遍 login 的代码，找不到一行把令牌打印出来的。",
    "写文件时还尽力把权限收紧到仅本人可读；Windows 不支持就优雅跳过——学习项目从简，生产要用 ACL 收紧。",
    # step9 Status
    "auth status 更简单：一百二十三行读本地令牌，没有就报未登录。",
    "有就一百二十九行本地比一下过期时间——注意，这是纯本地判断，不联网，断网也能查状态。",
    "一百三十二行报「已登录、有效期至几点」，但同样不打印令牌本身。刷新令牌换新的，是下一课 L13。",
    # step10 小抄+面试
    "收尾一张小抄。CLI 五个文件：入口 AgentLogCli、命令 AuthCommand、HTTP 客户端 ApiClient、非密配置 CliConfig、密凭据 CredentialStore。",
    "跑法记牢：终端 mvn package 打 jar，java -jar auth login 拿码，浏览器批准页输码，auth status 查状态。一手终端一手浏览器。",
    "面试或跟人讲就一句：CLI 是 AI 工具的手，用设备授权流登录，令牌只存不打印，config 非密和 credentials 密分家。",
]


def ffprobe_duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(path)],
        capture_output=True, text=True, check=True)
    return float(out.stdout.strip())


async def synth_all(parts_dir: Path):
    sem = asyncio.Semaphore(6)

    async def one(i: int, text: str):
        target = parts_dir / f"part{i:03d}.mp3"
        async with sem:
            for attempt in range(3):
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
        f"句数 {len(SENTENCES)} ≠ 预期 {EXPECTED_COUNT}，先与 07 HTML 的 MODEL 对齐再跑"
    ASSETS.mkdir(exist_ok=True)
    parts = ASSETS / "parts"
    parts.mkdir(exist_ok=True)

    print(f"① edge-tts 合成 {len(SENTENCES)} 句（音色 {VOICE}）…")
    asyncio.run(synth_all(parts))

    print("② 生成句间静音 + 拼接…")
    silence = parts / "silence.mp3"
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

    final = ASSETS / "L12-cli-session.mp3"
    subprocess.run(
        ["ffmpeg", "-y", "-v", "quiet", "-f", "concat", "-safe", "0",
         "-i", str(concat_list), "-c", "copy", str(final)],
        check=True)

    (ASSETS / "timeline.js").write_text(
        "// 由 07-make-audio.py 生成：每句在 L12-cli-session.mp3 里的 [start, end] 秒。\n"
        "window.AUDIO_TIMELINE = " + json.dumps(timeline) + ";\n",
        encoding="utf-8")

    print(f"③ 完成：{final.name}  共 {cursor / 60:.1f} 分钟，{final.stat().st_size / 1024 / 1024:.2f} MB")
    print(f"   时间轴：timeline.js（{len(timeline)} 句）")


if __name__ == "__main__":
    sys.exit(main())
