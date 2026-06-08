package com.agentlog;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * L02 核心测试：用机器强制校验模块边界 + 生成模块图。
 *
 * 这是一种你没见过的【新测试形态】——它既不是 @SpringBootTest，也不是 @WebMvcTest。
 * 它【不启动 Spring 容器、不连数据库、不发 HTTP 请求】，
 * 而是用 ArchUnit(底层)静态分析字节码里的【包依赖关系】，纯内存、极快。
 *
 * ApplicationModules.of(AgentLogApplication.class) 做的事：
 *   以 AgentLogApplication 所在包(com.agentlog)为根，
 *   把每个直接子包(identity/forum/...)识别成一个模块，shared 因标了 OPEN 视为开放模块。
 */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(AgentLogApplication.class);

    @Test
    void verifiesModuleStructure() {
        // verify() 会检查所有模块间依赖是否合法：
        //   - 业务模块不能 import 别的业务模块的私有子包
        //   - 不能有循环依赖
        //   - 访问 OPEN 模块(shared)不算违规
        // 任何违规 → 抛异常 → 测试红。这就是"边界靠机器强制,不靠自觉"。
        modules.verify();
    }

    @Test
    void writesDocumentationSnippets() {
        // 生成模块图(PlantUML)到 target/spring-modulith-docs/，满足 L02"模块图生成"验收。
        // 全部模块总览图 + 每个模块单独的图。
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                // writeModuleCanvases(): 生成"模块画布"——每个模块一张更详细的表格,
                // 列出对外暴露的类、监听/发布的事件、依赖的其他模块等(由主人 L02 亲手补上)。
                .writeModuleCanvases();
    }
}
