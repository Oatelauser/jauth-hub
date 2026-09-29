package io.github.oatelauser.jauth.core.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUIDv7 生成器：48 bit 毫秒 Unix 时间戳 + 12 bit 毫秒内单调计数器 + 62 bit 随机。
 *
 * <p>单调性采用 RFC 9562 §6.2 的计数器方法（方法一）：同一毫秒内 {@code rand_a} 位作为随机初值的递增计数器，
 * 保证生成序列在无符号比较下严格递增——主键按时间聚簇（索引局部性）且可排序。时钟回拨时沿用上次毫秒， 单调优先于时间精度（回拨窗口内 id 仍合法，仅时间戳略偏早）。
 *
 * <p>线程安全：全局锁足够（单机 id 生成的临界区为几十纳秒级），不值得引入更细粒度同步。
 *
 * @author oatelauser
 */
public final class UuidV7 {

    /** rand_a 计数器位宽（12 bit），即单毫秒最多 4096 个 id，超出则自旋等下一毫秒。 */
    private static final int COUNTER_BITS = 12;

    private static final int COUNTER_MAX = (1 << COUNTER_BITS) - 1;

    private static final long VERSION_7_BITS = 0x7000L;

    private static final long RFC_4122_VARIANT_BITS = 0x8000000000000000L;

    private static final long RANDOM_62_BITS_MASK = 0x3FFFFFFFFFFFFFFFL;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 上次生成所用毫秒与计数器：同步块内读写，保证同毫秒单调。 */
    private static long lastMillis;

    private static int counter;

    private UuidV7() {}

    /**
     * 生成单调递增的 UUIDv7。
     *
     * @return 新的 UUIDv7，版本位为 7、变体位为 RFC 4122
     */
    public static synchronized UUID generate() {
        long now = System.currentTimeMillis();
        if (now < lastMillis) {
            // 时钟回拨：沿用上次毫秒，避免序列倒退
            now = lastMillis;
        }
        if (now == lastMillis) {
            if (counter == COUNTER_MAX) {
                now = awaitNextMillis(lastMillis);
                counter = RANDOM.nextInt(COUNTER_MAX + 1);
            } else {
                counter++;
            }
        } else {
            counter = RANDOM.nextInt(COUNTER_MAX + 1);
        }
        lastMillis = now;

        long mostSignificantBits = (now << 16) | VERSION_7_BITS | counter;
        long leastSignificantBits = RFC_4122_VARIANT_BITS | (RANDOM.nextLong() & RANDOM_62_BITS_MASK);
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    private static long awaitNextMillis(long currentMillis) {
        long now = currentMillis;
        while (now <= currentMillis) {
            now = System.currentTimeMillis();
        }
        return now;
    }
}
