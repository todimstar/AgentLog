// Markdown 渲染与消毒 —— 全项目【唯一】持有 HTML 字符串的地方。
//
// 为什么需要它：正文块（机娘投稿 / 主人撰写）本来就是 Markdown 源文，
// 此前前端用 `{{ block.content }}` 纯文本插值直接吐出来，满屏 `#` 和 ``` 没有被渲染。
//
// 为什么不能直接 v-html：正文块是【不可信输入】——机娘写的内容说到底来自模型输出与外部资料，
// 主人也可能粘贴任意文本。未经消毒的 v-html 就是教科书级 XSS。
// 蓝图 06-web/frontend-architecture.md 的 Markdown 节写死了「禁止直接 v-html 输出未清理正文」，
// 09-security/threat-model.md 第一行威胁就是「XSS 读取认证 → Markdown DOMPurify」。
//
// 双层防御（两道都要，但分工必须说准）：
//   第一道 markdown-it { html: false } + 内建 validateLink
//        · html:false → 源文里的 <script>/<img onerror> 被转义成【字面量文本】，压根不生成 HTML 节点；
//        · validateLink → 黑名单拦 `vbscript|javascript|file|data:` 四种协议
//          （dist/index.cjs.js:5110 BAD_PROTO_RE，data:image/{gif,png,jpeg,webp} 例外放行），
//          拦下后连 <a> 标签都不组装，退回字面量。
//        实测（本课探针，10 种绕过载荷含实体编码/大小写/制表符）：这一道【全部挡住】。
//   第二道 DOMPurify.sanitize()
//        今天它并不是"拦下了第一道漏掉的东西"——它是【白名单保险】与外链 hook 的载体：
//        · 黑名单（第一道）随新协议出现会过期；白名单（第二道）默认拒绝一切未知标签/属性，方向天然更稳；
//        · 防【配置漂移】：哪天有人把 html 改成 true（"我想内嵌个 iframe"）、
//          装了个吐原始 HTML 的 markdown-it 插件、或撞上版本回归，第一道当场失效、第二道还在；
//        · target/rel/loading 的加固 hook 必须挂在它上面（见下）。
//   ——「今天没抓到东西」不等于「可以删」，安全防线的价值在于失效那天还在。
//
// 消毒时机 = 渲染时（前端），不是存储时。三条理由：
//   1. APPROVAL_RECORD 冻结的「Contribution 永不覆盖」——raw_content 必须是作者原文，存储时消毒＝篡改原始记录；
//   2. 规则可演进——将来发现新向量收紧规则，全部历史内容立刻受益；存储时消毒则历史数据无法补救；
//   3. 业界主流（GitHub / Discourse 皆如此）。
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

// 模块级单例：解析器初始化要构建整条规则链，不便宜，绝不在组件里 new。
const md = new MarkdownIt({
  html: false, // ★ 安全线：不解析源文里的原始 HTML
  linkify: true, // 裸 URL 自动成链接——开发日志里贴链接极常见
  breaks: true, // ★ 单换行 → <br>。机娘和开发者写日志习惯单换行分段，
  //   标准 Markdown 会把它们挤成一坨，对本项目是硬伤
  typographer: false, // 智能引号/破折号替换在中文语境会捣乱
})

// 代码高亮：V1 不接（蓝图明说「代码块可后续接高亮库，V1 先保证安全和排版」）。
// 另一条理由是包体：当前 bundle 已超 Vite 500KB 警戒线，highlight.js 全量 ~900KB 硬塞进主包会拖慢首屏。
// 将来要接，在上面的 options 里加：
//   highlight: (str, lang) => hljs.getLanguage(lang) ? `<pre><code>${hljs.highlight(str,{language:lang}).value}</code></pre>` : ''
// （返回空串＝退回 markdown-it 自己的转义输出；接的时候务必配 动态 import 按需加载。）

// 外链加固：给所有 <a> 补 target="_blank" + rel="noopener noreferrer"。
// 防的是 tabnabbing —— 被打开的页面能通过 window.opener 把原页面替换成钓鱼页。
//
// ★ 为什么用 DOMPurify 的 hook 而不是正则替换字符串：
//   拿正则改 HTML 是经典错误（HTML 不是正则语言，畸形标签能绕过）。
//   afterSanitizeAttributes 操作的是【已解析的 DOM 节点】，绕不过去。
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node instanceof Element && node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
  // 外链图片会把读者 IP 泄漏给第三方站点；V1 先做懒加载，图片代理留后续。
  if (node instanceof Element && node.tagName === 'IMG') {
    node.setAttribute('loading', 'lazy')
  }
})

/**
 * 把 Markdown 源文渲染成【已消毒】的 HTML 字符串。
 *
 * 只有 MarkdownContent.vue 该调它——其余任何地方都不该拿到 HTML 字符串，
 * 见 MarkdownContent.vue 顶部对「为什么封成组件而不是函数」的说明。
 */
export function renderMarkdown(source: string | null | undefined): string {
  if (!source) return ''
  // ADD_ATTR: 明示允许 target，免得将来 DOMPurify 收紧默认白名单时上面的 hook 白干。
  return DOMPurify.sanitize(md.render(source), { ADD_ATTR: ['target'] })
}
