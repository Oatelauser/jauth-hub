package io.github.oatelauser.jauth.core.web;

/**
 * 教学层开关 Provider：控制三页"发生了什么"说明块 / 流程图 / HTTP 日志位的显隐。
 *
 * <p>默认 true（SPEC §7）；宿主关闭教学层的属性绑定（{@code jauth-hub.educational=false}）由 starter 装配落地（B4）， core
 * 只提供此接口与默认常量。
 *
 * @author oatelauser
 */
public interface EducationalFlag {

    /** 默认实现：教学层开启。 */
    EducationalFlag ON = () -> true;

    /**
     * 教学层是否开启。
     *
     * @return true 表示渲染教学块/流程图/HTTP 日志位
     */
    boolean enabled();
}
