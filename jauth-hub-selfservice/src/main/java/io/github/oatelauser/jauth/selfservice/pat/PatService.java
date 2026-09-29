package io.github.oatelauser.jauth.selfservice.pat;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * PAT 域门面：创建 / 列表 / 吊销（表 jauth_pat，V2 建表）。memory/jdbc 双实现共用契约，测试以同一套抽象契约覆盖。
 *
 * <p><b>明文纪律</b>（SPEC §3）：明文令牌只在 {@link #create} 的返回值中出现一次，此后任何查询面（列表/审计/日志）
 * 只有 SHA-256 哈希与展示前缀。调用方（控制器）须把明文直接送进创建响应，禁止中转日志。
 *
 * <p><b>装配门控</b>（04 票：memory 模式禁用 PAT）：本接口的 bean 只在 {@code jauth-hub.storage=jdbc} 时注册——
 * PAT 是持久化语义的凭据，memory 模式（重启即失、面向 demo）不承载；页面侧渲染"当前存储模式不支持"提示。
 *
 * <p>last_used 留位：B7 审计/内省接线后由校验路径回填，本批接口不含更新方法。
 *
 * @author oatelauser
 */
public interface PatService {

    /**
     * 创建一枚 PAT：生成高熵明文令牌，落库哈希与前缀。
     *
     * <p>前置约束（控制器层守门）：scopes 非空且均在 ScopeCatalog 目录内；validity ∈ {30, 90, 365} 天（SPEC §6）。
     *
     * @param userId 归属用户 id（表 jauth_user.id）
     * @param scopes 授权 scope 集
     * @param validity 有效期
     * @return 记录 + 仅此一次的明文令牌
     */
    PatIssuance create(String userId, Set<String> scopes, Duration validity);

    /**
     * 列出用户名下未吊销的 PAT（含已过期行，过期以时间判定由展示层标注），按创建时间倒序。
     *
     * @param userId 用户 id
     * @return 未吊销记录列表
     */
    List<PatRecord> listActive(String userId);

    /**
     * 吊销一枚 PAT（所有权随 WHERE 收紧：他人 id 不可吊销）。
     *
     * @param userId 归属用户 id
     * @param patId 令牌记录 id
     * @throws io.github.oatelauser.jauth.core.response.JauthException B0502：记录不存在、非本人或已吊销
     */
    void revoke(String userId, String patId);

    /**
     * 创建结果：记录 + 明文令牌。
     *
     * <p>明文唯一出现点——消费方即写进创建响应，不做任何留存。
     *
     * @param record 落库后的记录
     * @param plaintextToken 明文令牌（仅此一次）
     */
    record PatIssuance(PatRecord record, String plaintextToken) {}
}
