package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * 「当前装配不支持」路径的真渲染回归（v1.3 D3，D0 记账 #7）：宿主未装 jauth 域 bean 时控制器早退
 * <b>不设 org</b>——守卫外的 {@code ${org.name}} 徽标对 null 取属性即 C0101，友好提示反而永不可达
 * （v1.2.1 模板解析器缺口同族）。哑控制器喂 supported=false + org 缺席的最小模型，走 standalone
 * MockMvc 真渲染（布局 fragment 的 @{...} 需要 Web 上下文）。
 *
 * @author oatelauser
 */
class UnsupportedAssemblyRenderTest {

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new DummyPageController())
                .setViewResolvers(viewResolver())
                .setLocaleResolver(new FixedLocaleResolver(Locale.SIMPLIFIED_CHINESE))
                .addFilters((request, response, chain) -> {
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    chain.doFilter(request, response);
                })
                .build();
    }

    @Test
    @DisplayName("org-apps：supported=false 且无 org → 不炸 C0101、渲染不支持提示")
    void orgAppsRendersUnsupportedAlertWithoutOrg() throws Exception {
        String html = this.mockMvc()
                .perform(get("/dummy/org-apps"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("C0101").contains("当前装配不支持");
    }

    @Test
    @DisplayName("org-installations：同上守卫回归")
    void orgInstallationsRendersUnsupportedAlertWithoutOrg() throws Exception {
        String html = this.mockMvc()
                .perform(get("/dummy/org-installations"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("C0101").contains("当前装配不支持");
    }

    /** 哑控制器：喂最小「装配不支持」模型（org 恒缺席——这正是被测早退形态）。 */
    @Controller
    static class DummyPageController {

        @GetMapping("/dummy/org-apps")
        public String orgApps(Model model) {
            model.addAttribute("educational", false);
            model.addAttribute("orgAppsSupported", false);
            return "org-apps";
        }

        @GetMapping("/dummy/org-installations")
        public String orgInstallations(Model model) {
            model.addAttribute("educational", false);
            model.addAttribute("installationsSupported", false);
            return "org-installations";
        }
    }

    private static ViewResolver viewResolver() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templateResolver("io/github/oatelauser/jauth/selfservice/web/templates/"));
        engine.addTemplateResolver(templateResolver("io/github/oatelauser/jauth/core/web/templates/"));
        engine.setMessageSource(messageSource());
        ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(engine);
        viewResolver.setContentType("text/html;charset=UTF-8");
        return viewResolver;
    }

    private static ClassLoaderTemplateResolver templateResolver(String prefix) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(prefix);
        resolver.setSuffix(".html");
        resolver.setCacheable(false);
        resolver.setCheckExistence(true);
        return resolver;
    }

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames(
                "io/github/oatelauser/jauth/selfservice/i18n/messages",
                "io/github/oatelauser/jauth/core/i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        return source;
    }
}
