# -*- coding: utf-8 -*-
"""
L11.5 会话与安全内幕 · 方案B 音频生成脚本（生成期工具，运行期零依赖）。

用法（Windows，仓库根目录）：
    .venv-tts/Scripts/python backend/src/magic-L11.5/06-make-audio.py

产出（提交入库，HTML 直接引用）：
    06-assets/L11.5-session.mp3   一整条旁白音频（句间留 0.45s 呼吸）
    06-assets/timeline.js         每句的 [start, end] 秒级时间轴（供 HTML 同步字幕/高亮）

⚠ SENTENCES 必须与 06-L11.5会话与安全内幕-方案B-mp3.html 里 MODEL 的句子
  完全同文同序（脚本会校验句数）。改旁白 → 两处同步改 → 重跑本脚本。
依赖：venv 里的 edge-tts（微软在线 TTS，需联网）；系统 PATH 里的 ffmpeg/ffprobe。
"""
import asyncio
import json
import subprocess
import sys
from pathlib import Path

import edge_tts

VOICE = "zh-CN-YunjianNeural"   # 与方案A浏览器端首选音色一致
GAP_SECONDS = 0.45              # 句间停顿
ASSETS = Path(__file__).parent / "06-assets"
EXPECTED_COUNT = 46             # 与 HTML MODEL 句数对齐（防两边改漏）

# 按（步序, 句序）摊平的全部旁白。与 06 HTML 的 MODEL.steps[*].sentences 同文同序。
SENTENCES = [
    # step0 登录四拍
    "登录整个入口就这三十行，四拍节奏：先限流，再认证，然后存会话，最后回用户视图。",
    "第零拍，八十二行，先问 Redis：这个邮箱是不是已经连错五次被锁十五分钟。认证之前先拦，防暴力撞库。",
    "第一拍，八十七行，把邮箱和明文密码包成一个 Token，整个交给 AuthenticationManager。密码对不对，完全不归 Controller 管。",
    "失败走八十九到九十一行：失败计数加一，然后原样抛出，由 Security 统一翻成四零一。",
    "第二拍，九十六到一百行：成功后清掉失败计数，把认证结果装进一个全新的 SecurityContext，再显式存进 HttpSession。一百行这个 saveContext，就是整个记住你的开关，线二专门拆它。",
    "最后一百零二行，从认证结果里取 Name，解析成用户 id 查库回视图。为什么 Name 里是 id 不是名字？线一揭晓。",
    # step1 入参校验
    "今晨刚加固的一环：登录请求体上了注解校验。十五行的 Pattern 要求邮箱必须是合法格式，Controller 参数标了 Valid，非法格式在进方法体之前就被四百打回。",
    "这一下顺手堵了你实测出来的洞：以前拿字符 1 当邮箱登录，Redis 也会老实建一个失败计数键；现在垃圾串根本进不了门，Redis 干干净净。",
    "十六行密码只查非空、不查长度——登录不该复述注册策略，存量账号和演示账号的密码规则可能不一样。",
    # step2 一行里的三步
    "现在拆那一行 authenticate。它是接口 AuthenticationManager 的方法，默认实现叫 ProviderManager。它自己不干活，把认证请求分发给一串 Provider。",
    "对用户名密码这种登录，接活的是 DaoAuthenticationProvider，内部就三步。",
    "第一步，回调 loadUserByUsername，按邮箱把用户查出来。注意，这个方法是你自己写的，框架只是来调它。",
    "第二步，用 PasswordEncoder 把前端传来的明文，和库里的 bcrypt 哈希做比对。",
    "第三步，对，就返回一个已认证的 Authentication；错，就抛异常。你旧项目自己写的那一堆查库比密码，其实就是在重新发明这个 Provider。",
    # step3 你写的那一环
    "这就是被框架回调的那个类。三十三行，方法名 loadUserByUsername 是接口定死的，改不了；但三十六三十七行的方法体，我们改成按 email 查库——参数名义上叫 username，装的其实是邮箱。",
    "所以换登录字段不等于重写认证：整条 Provider 流水线一行不动，只换这一个查法。你旧项目为邮箱登录写的那一大堆，正是没吃到这个复用。",
    "四十三到四十六行是第二个关键约定：返回的 User，name 字段塞的不是用户名，是数据库主键 id 的字符串。",
    "这样认证成功后 authentication.getName 拿到的直接就是 id，Controller 一百零二行 parseLong 回来就是 currentUserId，后面所有行级授权都靠它。",
    # step4 Bean 从哪来
    "你问 AuthenticationManager 到底在哪用到了。除了 Controller 那一行，还有这里：七十九行把它注册成 Bean，Controller 构造器里才注入得到它。",
    "七十三行的 bcrypt 编码器同理：注册时 encode 存哈希，登录时 matches 比对，用的是同一个 Bean。",
    "那 Provider 怎么知道用你的 UserDetailsService 和这个编码器？Spring 启动时发现容器里有这两种 Bean，自动装配进 DaoAuthenticationProvider。你没写的那部分装配，就发生在这一步。",
    # step5 存的一半
    "线二，记住身份的最小单元。你说没找到取 Cookie 恢复上下文的代码——因为要你写的只有存这一半，取那一半是框架的。",
    "一百行 saveContext 一口气干两件事：把 SecurityContext 塞进 HttpSession；如果这是第一次建会话，还往响应里写一个 Set-Cookie，JSESSIONID 等于一串随机数。",
    "这就回答了为什么要传 req 和 resp：req 用来拿到或者创建 HttpSession，resp 用来把钥匙发给浏览器。",
    "记牢这个比喻：柜子在服务端，是 HttpSession；钥匙在浏览器，是 JSESSIONID 这个 Cookie。saveContext 就是把东西放进柜子，再把钥匙递出去。",
    # step6 取的一半(你没写)
    "取的那一半在这。浏览器对同域请求会自动带上 JSESSIONID，你一行代码都不用写。",
    "服务端的 SecurityContextHolderFilter 在过滤器链前排接住它：拿钥匙开柜子，把登录时存的 SecurityContext 取出来，放回线程上下文。",
    "所以你项目里 CurrentUser.requireId 才总能拿到人——它读的就是这个线程上下文。",
    "请求一结束，线程上下文就清空，下个请求重新从 session 恢复，线程复用也不会串号。至于 csrfController，跟这套完全无关：它只负责发 CSRF 令牌，防的是另一种攻击，线三讲。",
    # step7 柜子在哪
    "柜子本体存在哪？就是 Tomcat 进程里、JVM 堆内存中的一个 Map：键是 session id，值是 session 对象。",
    "所以你那个问题的答案是：重启后全没。内存态，进程一停柜子全清，所有人重新登录。",
    "要重启不掉线，就把柜子搬出去——接 Spring Session 加 Redis，把会话存进外部存储。蓝图里排在多实例部署那课，现在不接。",
    "四十七四十八行的 IF_REQUIRED 是本项目跟旧项目的分水岭：旧项目 JWT 是 STATELESS，永不建会话；本项目需要时就建。这一行，就是 Session 派的宣言。",
    # step8 销柜子+试金石
    "闭环的另外两个端点。一百零九行登出：invalidate 直接把柜子销毁。钥匙还在浏览器里，但已经开不了任何柜子——服务端说了算。这是 Session 相对 JWT 的核心优势：可即时撤销。",
    "一百一十四行的 me 是刷新仍登录的试金石：带着 Cookie 来，过滤器恢复上下文，返回用户；柜子没了，就四零一。",
    "到这，Session 管登录的最小单元凑齐了，就三件：登录时 saveContext，存柜子发钥匙；每次请求，过滤器拿钥匙开柜子；登出 invalidate，销柜子。其他全是这三件的周边。",
    # step9 门禁规则
    "线三，边界防线。五十六五十七行，登录、注册、发码必须匿名可进——还没登录的人，才需要这几个门。",
    "六十三行一句 anyRequest authenticated，把其余所有接口都关进要有会话的门里。",
    "没带有效钥匙的，六十六六十七行统一回四零一——语义是不知道你是谁，去登录。前端拿到四零一就跳登录页。",
    # step10 两个Cookie分工
    "最后分清两个 Cookie 的职责——你之前把 csrfController 和会话恢复混到一起了。JSESSIONID 负责你是谁，它是 HttpOnly 的，脚本读不到。",
    "XSRF-TOKEN 负责这个请求真是你发的：三十三行故意不设 HttpOnly，让前端 JS 读出来放进请求头，服务端比对 Cookie 和 Header 是否一致。攻击者能让你的浏览器带 Cookie，却读不到你域下的这个值，伪造请求就断在这。",
    "四十一到四十五行给登录注册发码开了 CSRF 豁免：这时候人还没会话，没有可被冒用的身份。",
    "正因为认证靠 Cookie，CSRF 必须开；旧项目 JWT 放 Header，浏览器不会自动携带，才敢关。一开一关，背后是同一条逻辑。",
    # step11 小抄+面试答法
    "收个尾。这张小抄就是你要的最小单元：登录两行，恢复零行，登出一行。",
    "面试被问 Session 和 JWT，就一句：Session 把状态放服务端，能即时撤销，代价是服务端要存；JWT 把状态放令牌里，好横向扩展，代价是难撤销。同源单页应用，选 Session 更简单。",
    "被问邮箱登录怎么改，答：认证流水线不动，只把 UserDetailsService 的查法从用户名换成邮箱，一行都不用多写。这套答案，比背八股扎实多了。",
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

    final = ASSETS / "L11.5-session.mp3"
    subprocess.run(
        ["ffmpeg", "-y", "-v", "quiet", "-f", "concat", "-safe", "0",
         "-i", str(concat_list), "-c", "copy", str(final)],
        check=True)

    (ASSETS / "timeline.js").write_text(
        "// 由 06-make-audio.py 生成：每句在 L11.5-session.mp3 里的 [start, end] 秒。\n"
        "window.AUDIO_TIMELINE = " + json.dumps(timeline) + ";\n",
        encoding="utf-8")

    print(f"③ 完成：{final.name}  共 {cursor / 60:.1f} 分钟，{final.stat().st_size / 1024 / 1024:.2f} MB")
    print(f"   时间轴：timeline.js（{len(timeline)} 句）")
    print("   分句缓存在 06-assets/parts/（可删，重跑会重建）")


if __name__ == "__main__":
    sys.exit(main())
