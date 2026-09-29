package io.github.oatelauser.jauth.resourceserver;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 手写小型 TTL 缓存（06/08 票：不引 Caffeine/Guava，~50 行约束）。
 *
 * <p>并发模型：全部方法 synchronized 粗粒度锁——内省 QPS 低压场景下一把锁足够（无锁化收益不及复杂度），
 * ponytail: 全局锁为天花板，若多实例高 QPS 打爆再评估分锁或换 Caffeine。
 *
 * <p>淘汰策略：access-order {@link LinkedHashMap} 的 LRU 作容量护栏（removeEldestEntry），过期靠惰性清理
 * （get 命中过期即删；put 前顺带清扫全部已过期项，防冷键长期占额）。
 *
 * @param <V> 缓存值类型（{@link CachingIntrospector} 以 {@code Optional} 包装表达负缓存）
 */
final class TtlBoundedCache<V> {

    private record Entry<V>(V value, long expiresAtMillis) {}

    private final int maxEntries;

    private final Clock clock;

    private final LinkedHashMap<String, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry<V>> eldest) {
            return size() > TtlBoundedCache.this.maxEntries;
        }
    };

    TtlBoundedCache(int maxEntries, Clock clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1, got: " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /**
     * 命中且未过期返回值；未命中或已过期（顺带删除）返回 {@code null}。
     *
     * <p>注意：值本身可为 {@code null} 语义（如 Optional.empty），仅"返回 null"表示未命中。
     */
    @Nullable
    synchronized V get(String key) {
        Entry<V> entry = this.entries.get(key);
        if (entry == null) {
            return null;
        }
        if (this.clock.millis() >= entry.expiresAtMillis) {
            this.entries.remove(key);
            return null;
        }
        return entry.value;
    }

    synchronized void put(String key, V value, Duration ttl) {
        // 先清过期再写入：给被过期项占用的名额腾位，避免无谓挤掉活跃 LRU
        this.entries
                .entrySet()
                .removeIf(e -> this.clock.millis() >= e.getValue().expiresAtMillis());
        this.entries.put(key, new Entry<>(value, this.clock.millis() + ttl.toMillis()));
    }
}
