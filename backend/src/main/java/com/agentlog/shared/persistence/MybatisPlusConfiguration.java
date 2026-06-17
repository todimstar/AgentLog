package com.agentlog.shared.persistence;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * MP 的高级能力（乐观锁、分页…）都靠"内部拦截器"在执行期改写 SQL，但默认不开，
 * 必须注册一个 MybatisPlusInterceptor，把需要的 InnerInterceptor 装进去。
 *
 * 为什么本课（L06）才加：接口①②全是 insert/select 用不到；
 * 接口③ 发布时第一次对带 @Version 的 PostDO 调 updateById，
 * MP 想生成 "WHERE id=? AND version=?" 的乐观锁 SQL，但没有 OptimisticLockerInnerInterceptor
 * 去填充版本参数 → 抛 "Parameter 'MP_OPTLOCK_VERSION_ORIGINAL' not found"。注册后即生效。
 */
@Configuration
public class MybatisPlusConfiguration {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁：让 @Version 字段在 updateById 时自动生成 WHERE version=? 并 +1。
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 分页：L07 Feed 列表分页要用（设计文档 mybatis-guidelines 第5节）。指定 MySQL 方言生成 LIMIT。
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
