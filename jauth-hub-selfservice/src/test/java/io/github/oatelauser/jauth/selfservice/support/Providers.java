package io.github.oatelauser.jauth.selfservice.support;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 测试用固定值 {@link ObjectProvider}（Spring 7 的 ObjectProvider 非函数接口，lambda 不可用）。
 *
 * @author oatelauser
 */
public final class Providers {

    private Providers() {}

    /**
     * 恒定返回给定值的 provider。
     *
     * @param value 目标 bean
     * @param <T> bean 类型
     * @return provider
     */
    public static <T> ObjectProvider<T> fixed(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }
}
