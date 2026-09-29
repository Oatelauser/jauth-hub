package io.github.oatelauser.jauth.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link UuidV7} 规范单测：版本位、变体位、千次唯一性与无符号单调性。
 *
 * @author oatelauser
 */
class UuidV7Test {

    private static final int SAMPLE_COUNT = 1000;

    @Test
    void versionAndVariantFollowRfc9562() {
        UUID uuid = UuidV7.generate();
        assertThat(uuid.version()).as("版本位应为 7").isEqualTo(7);
        assertThat(uuid.variant()).as("变体位应为 RFC 4122").isEqualTo(2);
    }

    @Test
    void thousandGenerationsAreUnique() {
        Set<UUID> generated = new HashSet<>();
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            generated.add(UuidV7.generate());
        }
        assertThat(generated).as("1000 次生成应全不重复").hasSize(SAMPLE_COUNT);
    }

    @Test
    void thousandGenerationsAreMonotonicUnsigned() {
        UUID previous = UuidV7.generate();
        for (int i = 1; i < SAMPLE_COUNT; i++) {
            UUID current = UuidV7.generate();
            assertThat(Long.compareUnsigned(previous.getMostSignificantBits(), current.getMostSignificantBits()))
                    .as("第 %d 次生成不得倒退（无符号比较最高 64 位）", i)
                    .isNegative();
            previous = current;
        }
    }

    @Test
    void embedsCurrentUnixMillis() {
        long before = System.currentTimeMillis();
        UUID uuid = UuidV7.generate();
        long after = System.currentTimeMillis();
        long embeddedMillis = uuid.getMostSignificantBits() >>> 16;
        assertThat(embeddedMillis).isBetween(before, after);
    }
}
