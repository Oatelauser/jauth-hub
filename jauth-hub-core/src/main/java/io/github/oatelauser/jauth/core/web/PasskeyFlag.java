package io.github.oatelauser.jauth.core.web;

/**
 * Passkey 可见性开关 Provider（v1.2 C2，照 {@link EducationalFlag} 先例）：登录页"使用通行密钥登录"
 * 按钮与脚本块、看板导航入口只在 {@code jauth-hub.passkey.enabled=true}（SPEC §5 默认关）时渲染。
 *
 * <p>core 不依赖装配属性——开关经本接口注入，starter 以属性绑定落地，宿主可以自有 bean 覆盖。
 * 默认 {@link #OFF}：未注入即视为关闭，页面零可见变化。
 *
 * @author oatelauser
 */
public interface PasskeyFlag {

    /** 默认实现：passkey 关闭（与 jauth-hub.passkey.enabled 缺省一致）。 */
    PasskeyFlag OFF = () -> false;

    /**
     * passkey 是否开启。
     *
     * @return true 表示登录页/导航渲染 passkey 入口
     */
    boolean enabled();
}
