// Markdown 渲染 + XSS 消毒的回归测试。
//
// 为什么这套测试是本次最值钱的产物：安全防线【必须】有自动化回归。
// markdown-it 的 html:false、DOMPurify 的调用、外链 hook——任何一处被人手滑改掉，
// 页面看起来【一切正常】，只有攻击者知道防线没了。人眼审不出来，测试能。
//
// ★ 断言方式的教训（第一版这套测试写错过，3 条红）：
//   最初写的是 `expect(html).not.toContain('onerror')` 这种【字符串断言】——错的。
//   因为 html:false 会把 `<img src=x onerror=alert(1)>` 转义成文本 `&lt;img ... onerror=...&gt;`，
//   字符串里当然还有 "onerror" 这几个字母，但它只是【一段字】，浏览器不会当属性执行。
//   XSS 的真问题从来不是"输出里有没有这个词"，而是"浏览器会不会造出危险的 DOM 节点"。
//   所以正确姿势 = 把结果塞进真 DOM 再【结构化】检查。这也正是 DOMPurify 自己的工作方式。
//
// 运行环境是 jsdom（见 vite.config.ts 的 test.environment）：DOMPurify 与本文件都需要真 DOM。
import { describe, it, expect } from 'vitest'
import { renderMarkdown } from '../markdown'

/** 把渲染结果塞进真 DOM——之后所有安全断言都问"DOM 里有什么"，而不是"字符串里有什么"。 */
function renderToDom(source: string): HTMLDivElement {
  const host = document.createElement('div')
  host.innerHTML = renderMarkdown(source)
  return host
}

/** 全树扫一遍有没有 on* 事件处理器属性（onerror / onload / onmouseover …）。 */
function eventHandlerAttrs(root: Element): string[] {
  return Array.from(root.querySelectorAll('*')).flatMap((el) =>
    Array.from(el.attributes)
      .map((a) => a.name.toLowerCase())
      .filter((n) => n.startsWith('on')),
  )
}

describe('renderMarkdown · XSS 防护（结构化断言：看 DOM，不看字符串）', () => {
  const PAYLOADS = [
    '<script>alert(1)</script>',
    '<img src=x onerror=alert(1)>',
    '<svg onload=alert(1)>',
    '<iframe src="https://evil.example"></iframe>',
    '<a href="javascript:alert(1)">点我</a>',
    '<body onload=alert(1)>',
    '[点我](javascript:alert(1))',
    '[点我](java&#115;cript:alert(1))', // 实体编码绕过尝试
    '[点我](JaVaScRiPt:alert(1))', // 大小写绕过尝试
    '[点我](vbscript:msgbox(1))',
    '![图](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)',
  ]

  it.each(PAYLOADS)('载荷 %j 不产生可执行节点', (payload) => {
    const dom = renderToDom(payload)
    expect(dom.querySelector('script')).toBeNull()
    expect(dom.querySelector('iframe')).toBeNull()
    expect(dom.querySelector('object,embed,form')).toBeNull()
    expect(eventHandlerAttrs(dom)).toEqual([]) // 一个 on* 属性都不许有
    for (const a of dom.querySelectorAll('a')) {
      expect(a.getAttribute('href')?.toLowerCase() ?? '').not.toMatch(/^(javascript|vbscript|file|data):/)
    }
    for (const img of dom.querySelectorAll('img')) {
      // data:image/png 这类 markdown-it 明确放行；危险的是 data:text/html 与 svg+xml
      expect(img.getAttribute('src')?.toLowerCase() ?? '').not.toMatch(/^data:(?!image\/(gif|png|jpeg|webp);)/)
    }
  })

  it('第一道防线 html:false —— 内嵌 HTML 被降级成【文本】而不是被悄悄丢掉', () => {
    const dom = renderToDom('正常文字\n\n<script>alert(1)</script>')
    expect(dom.querySelector('script')).toBeNull()
    // 内容仍在，只是变成了字面量：用户写了什么读者就看到什么，既安全又不丢信息
    expect(dom.textContent).toContain('<script>alert(1)</script>')
  })

  it('第一道防线还包含协议黑名单 —— javascript: 链接压根不会被组装成 <a>', () => {
    // markdown-it 的 validateLink 拦截 vbscript|javascript|file|data 四种协议
    //（dist/index.cjs.js:5110 BAD_PROTO_RE），拦下后【退回字面量文本】，连标签都不生成。
    const dom = renderToDom('[点我](javascript:alert(1))')
    expect(dom.querySelector('a')).toBeNull()
    expect(dom.textContent).toContain('[点我](javascript:alert(1))')
  })

  it('空/undefined 源文返回空串，不抛异常', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null)).toBe('')
    expect(renderMarkdown(undefined)).toBe('')
  })
})

describe('renderMarkdown · 正常排版', () => {
  it('标题渲染成 h1/h2', () => {
    const html = renderMarkdown('# 一级\n\n## 二级')
    expect(html).toContain('<h1>一级</h1>')
    expect(html).toContain('<h2>二级</h2>')
  })

  it('无序列表渲染成 ul/li', () => {
    const dom = renderToDom('- 甲\n- 乙')
    expect(dom.querySelectorAll('ul > li')).toHaveLength(2)
  })

  it('围栏代码块渲染成 pre>code 且内容被转义（代码里的尖括号是文本，不是标签）', () => {
    const dom = renderToDom('```java\nSystem.out.println("<hi>");\n```')
    const code = dom.querySelector('pre > code')
    expect(code).not.toBeNull()
    expect(code!.querySelector('hi')).toBeNull()
    expect(code!.textContent).toContain('<hi>')
  })

  it('表格渲染成 table（markdown-it 默认开 GFM 表格）', () => {
    const dom = renderToDom('| a | b |\n| --- | --- |\n| 1 | 2 |')
    expect(dom.querySelectorAll('table th')).toHaveLength(2)
    expect(dom.querySelectorAll('table tbody td')).toHaveLength(2)
  })

  it('行内代码渲染成 code', () => {
    expect(renderMarkdown('用 `mvn test` 跑')).toContain('<code>mvn test</code>')
  })

  it('breaks:true —— 单换行变 <br>（机娘和开发者写日志的习惯，不能被挤成一坨）', () => {
    expect(renderToDom('第一行\n第二行').querySelector('br')).not.toBeNull()
  })

  it('linkify —— 裸 URL 自动成链接', () => {
    const a = renderToDom('见 https://example.com 文档').querySelector('a')
    expect(a?.getAttribute('href')).toBe('https://example.com')
  })
})

describe('renderMarkdown · 外链加固（这一段是 DOMPurify hook 的功劳，markdown-it 不做）', () => {
  it('链接带 target=_blank 与 rel=noopener noreferrer（防 tabnabbing）', () => {
    const a = renderToDom('[文档](https://example.com)').querySelector('a')!
    expect(a.getAttribute('target')).toBe('_blank')
    expect(a.getAttribute('rel')).toBe('noopener noreferrer')
  })

  it('图片带 loading=lazy', () => {
    const img = renderToDom('![图](https://example.com/a.png)').querySelector('img')!
    expect(img.getAttribute('loading')).toBe('lazy')
  })
})
