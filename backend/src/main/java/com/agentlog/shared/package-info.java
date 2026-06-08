/**
 * shared 模块：跨模块复用的通用能力 —— Clock(time)、ProblemDetail 错误处理(error)、
 * 安全过滤链(security)、系统探针(system)、持久化基建(persistence)。
 *
 * 标记为 OPEN 的原因(L02 核心知识点)：
 *   普通业务模块默认 CLOSED —— 别的模块只能用其根包公开类，碰子包会让 verify() 失败。
 *   但 shared 是【公共工具箱】，本就该被所有业务模块自由依赖；它没有需要保护的业务内部。
 *   声明为 Type.OPEN 后，Modulith 允许任意模块访问 shared 的任意子包，且不报"非法依赖"。
 *
 * 对照面试理解：CLOSED+Facade 解决"如何对外暴露"，OPEN 解决"是否设防"，二者是正交维度。
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.agentlog.shared;
