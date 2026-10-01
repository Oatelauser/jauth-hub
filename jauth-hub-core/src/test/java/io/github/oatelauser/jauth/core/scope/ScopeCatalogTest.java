package io.github.oatelauser.jauth.core.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * scope 目录：内置三枚 OIDC 标准 scope、宿主注册自定义、同名 upsert、快照排序与并发注册安全（p3c 并发章）。
 *
 * @author oatelauser
 */
class ScopeCatalogTest {

    @Test
    @DisplayName("内置枚举：openid/profile/email 三枚预注册，i18nKey 走统一前缀")
    void preRegistersThreeOidcBuiltins() {
        ScopeCatalog catalog = new InMemoryScopeCatalog();

        assertEquals(3, catalog.all().size());
        for (ScopeCatalog.Builtin builtin : ScopeCatalog.Builtin.values()) {
            String name = builtin.name().toLowerCase(Locale.ROOT);
            Optional<ScopeDefinition> definition = catalog.find(name);
            assertTrue(definition.isPresent(), "builtin scope missing: " + name);
            assertEquals("jauth.scope." + name, definition.orElseThrow().i18nKey());
            assertFalse(definition.orElseThrow().sensitive());
        }
    }

    @Test
    @DisplayName("宿主注册自定义 scope：可查得、计入全量")
    void hostRegistersCustomScope() {
        ScopeCatalog catalog = new InMemoryScopeCatalog();
        catalog.register(ScopeDefinition.of("repo:read", true));

        Optional<ScopeDefinition> registered = catalog.find("repo:read");
        assertTrue(registered.isPresent());
        assertTrue(registered.orElseThrow().sensitive());
        assertEquals("jauth.scope.repo:read", registered.orElseThrow().i18nKey());
        assertEquals(4, catalog.all().size());
    }

    @Test
    @DisplayName("同名重复注册为 upsert：覆盖旧定义，计数不涨")
    void registerSameNameUpserts() {
        ScopeCatalog catalog = new InMemoryScopeCatalog();
        catalog.register(ScopeDefinition.of("repo:read", false));
        catalog.register(ScopeDefinition.of("repo:read", true));

        assertEquals(4, catalog.all().size());
        assertTrue(catalog.find("repo:read").orElseThrow().sensitive());
    }

    @Test
    @DisplayName("工厂形态（v1.2 C4）：两参兼容形态落 fallbackDesc=null；三参形态携带 desc，i18nKey 同前缀")
    void factoryFormsCarryFallbackDesc() {
        assertNull(ScopeDefinition.of("repo:read", false).fallbackDesc());

        ScopeDefinition withDesc = ScopeDefinition.of("repo:read", false, "读取仓库");
        assertEquals("读取仓库", withDesc.fallbackDesc());
        assertFalse(withDesc.sensitive());
        assertEquals("jauth.scope.repo:read", withDesc.i18nKey());
    }

    @Test
    @DisplayName("all() 返回按名排序的快照：与后续注册隔离")
    void allReturnsSortedDetachedSnapshot() {
        ScopeCatalog catalog = new InMemoryScopeCatalog();
        catalog.register(ScopeDefinition.of("admin:all", false));

        List<ScopeDefinition> snapshot = catalog.all();
        catalog.register(ScopeDefinition.of("zzz:last", false));

        assertEquals(4, snapshot.size(), "snapshot must not observe later registrations");
        assertEquals("admin:all", snapshot.get(0).name());
        assertEquals("email", snapshot.get(1).name());
        assertEquals("openid", snapshot.get(2).name());
        assertEquals("profile", snapshot.get(3).name());
    }

    @Test
    @DisplayName("并发注册与查找安全：多线程注册全部落库、无异常")
    void concurrentRegisterAndFindIsSafe() throws Exception {
        ScopeCatalog catalog = new InMemoryScopeCatalog();
        int threads = 8;
        int perThread = 500;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                int index = t;
                tasks.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        String name = "custom:" + index + "-" + i;
                        catalog.register(ScopeDefinition.of(name, false));
                        if (catalog.find(name).isEmpty()) {
                            return false;
                        }
                    }
                    // 并发读侧：内置三枚始终可查
                    return catalog.find("openid").isPresent()
                            && catalog.find("profile").isPresent()
                            && catalog.find("email").isPresent();
                });
            }
            for (Future<Boolean> result : pool.invokeAll(tasks)) {
                assertTrue(result.get(), "a worker lost a registration or a builtin");
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(3 + (long) threads * perThread, catalog.all().size());
    }
}
