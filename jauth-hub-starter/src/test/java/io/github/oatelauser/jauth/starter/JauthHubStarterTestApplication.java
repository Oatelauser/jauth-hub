package io.github.oatelauser.jauth.starter;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 装配集成测试的宿主壳：模拟"宿主引入 jauth-hub-starter"——本包无组件扫描目标，全部装配来自
 * META-INF/spring 的 AutoConfiguration.imports（B4 接管式自动配置的入口）。
 *
 * @author oatelauser
 */
@SpringBootApplication
public class JauthHubStarterTestApplication {}
