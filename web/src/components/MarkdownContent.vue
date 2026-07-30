<script setup lang="ts">
// 正文块的 Markdown 渲染组件（组件名由蓝图 06-web/frontend-architecture.md 组件树钦定）。
//
// ★ 为什么封成【组件】，而不是函数或指令——这是本组件存在的全部意义：
//
//   函数 renderMd(src) → string    调用方还得自己配 v-html，
//                                  「消毒」和「输出」之间留了缝，总有人会忘。
//   指令 v-markdown="src"          挡不住旁边有人直接写 v-html。
//   组件 <MarkdownContent :source>  调用方【根本拿不到】中间的 HTML 字符串，
//                                  结构上不可能输出未消毒内容。
//
// 蓝图那句「禁止直接 v-html 输出未清理正文」是条纪律，而纪律靠人守、结构靠机器守。
// 同 L14 用 AgentIdentity 接口守模块边界一个思路：能用结构约束的，就别只写在文档里。
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{ source?: string | null }>()

// computed 缓存：source 不变就不重新解析。
const html = computed(() => renderMarkdown(props.source))
</script>

<template>
  <!-- 自带 .markdown-body：本组件在任何地方（详情页/审稿页/将来 L19 编辑预览）都能独立工作。
       样式在全局 styles/markdown.css，【不能】写成 scoped——
       scoped 靠给元素打 data-v-xxx 属性生效，而 v-html 插入的节点是运行时产物、拿不到这个属性。 -->
  <div class="markdown-body" v-html="html"></div>
</template>
