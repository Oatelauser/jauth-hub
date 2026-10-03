package io.github.oatelauser.jauth.app.configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * front 静态装配（v1.4 B4；v1.5 B5a 起产物来源 = jauth-hub-front-dist 依赖，B5b 起 front 唯一皮）：
 * {@code jauth-hub-front} 构建产物落 {@code classpath:/static/front/}，本配置把 {@code /front/**}
 * 出网并为 history 路由深链提供 index.html 回退——{@code /front/login} 无物理文件，不回退即 404，
 * SPA 深链与刷新直接断；带扩展名的 miss（如 {@code /front/logo.png}）照常 404，防静态 404 被吞成
 * index 页。访问控制在 default 链（{@link AppSecurityConfiguration} 的 {@code /front/**} permitAll
 * ——登录页本尊在皮内，必须匿名可达）。
 *
 * <p>资源位置两路并列：打包位（{@code classpath:/static/front/}）+ {@code spring.web.resources.static-locations}
 * 原值——后者使本地免打包联调一行配置可达：{@code static-locations=file:../jauth-hub-front/dist} 直指
 * dist 根（见 README），与打包形态的 front 子目录语义互不干扰。
 *
 * @author oatelauser
 */
@Configuration(proxyBeanMethods = false)
public class AppWebConfiguration {

    /** 打包形态的资源位：jauth-hub-front-dist jar 携带的 classpath 目录（与 app pom 依赖对齐）。 */
    static final String FRONT_PACKAGED_LOCATION = "classpath:/static/front/";

    @Bean
    WebMvcConfigurer frontResourceConfigurer(WebProperties webProperties) {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                // 打包位在前（制品形态命中即止）；配置的原值在后——dev 联调时直指 file:.../dist
                List<String> locations = new ArrayList<>();
                locations.add(FRONT_PACKAGED_LOCATION);
                locations.addAll(List.of(webProperties.getResources().getStaticLocations()));
                registry.addResourceHandler("/front/**")
                        .addResourceLocations(locations.toArray(String[]::new))
                        .resourceChain(true)
                        .addResolver(new FrontHistoryFallbackResolver());
            }
        };
    }

    /**
     * history 深链回退解析器：无扩展名的 miss 回同位置 index.html（路由由 SPA 接管）；带扩展名的
     * miss 返回 null 照常 404。逐位置解析（父类语义）：打包位回退不可读（dev 无拷贝）会继续尝试
     * 后续位置，dev 联调的 dist 才有机会命中。
     */
    static final class FrontHistoryFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null) {
                return requested;
            }
            if (resourcePath.indexOf('.') != -1) {
                return null;
            }
            Resource index = location.createRelative("index.html");
            return index.isReadable() ? index : null;
        }
    }
}
