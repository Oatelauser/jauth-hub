package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.SudoGate;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ui.Model;
import org.springframework.validation.support.BindingAwareModelMap;

/**
 * sudo 验证页单测（v1.2 C3）：returnTo 只认本站路径（外站/协议相对/空一律回退看板）；
 * sudo/passkey 任一缺席渲染"未启用"提示态（模型位驱动，不 500）。直调控制器不走 MockMvc——
 * standalone 场景无视图解析器，视图名渲染会循环派发（UrlFilenameView 语义），模型断言已覆盖本页职责。
 *
 * @author oatelauser
 */
class SudoControllerTest {

    @Test
    @DisplayName("returnTo 本站路径透传，外站/协议相对/空回退看板")
    void returnToSanitized() {
        SudoController controller = controller(true);

        assertThat(page(controller, "/profile")).containsEntry("returnTo", "/profile");

        assertThat(page(controller, "https://evil.example.com/phish"))
                .containsEntry("returnTo", SudoController.FALLBACK_RETURN_TO);
        assertThat(page(controller, "//evil.example.com")).containsEntry("returnTo", SudoController.FALLBACK_RETURN_TO);
        assertThat(page(controller, null)).containsEntry("returnTo", SudoController.FALLBACK_RETURN_TO);
    }

    @Test
    @DisplayName("SudoGate 缺席（sudo 关）渲染未启用提示态；双开时 sudoEnabled=true")
    void disabledStateRendersHint() {
        assertThat(page(controller(false), "/profile"))
                .containsEntry("sudoEnabled", false)
                .containsEntry("returnTo", "/profile");
        assertThat(page(controller(true), "/profile")).containsEntry("sudoEnabled", true);
    }

    private static Map<String, Object> page(SudoController controller, @Nullable String returnTo) {
        Model model = new BindingAwareModelMap();
        String view = controller.page(returnTo, model);
        assertThat(view).isEqualTo(SudoController.VIEW_SUDO);
        return model.asMap();
    }

    private static SudoController controller(boolean sudoEnabled) {
        SudoGate gate = sudoEnabled
                ? new SudoGate(new InMemoryUserRepository(), Duration.ofMinutes(15), Clock.systemUTC())
                : null;
        return new SudoController(provider(gate), (PasskeyFlag) () -> true, (EducationalFlag) () -> true);
    }

    /** 按在场性返回固定 gate 的最小 ObjectProvider（控制器只读 getIfAvailable）。 */
    private static ObjectProvider<SudoGate> provider(@Nullable SudoGate gate) {
        return new ObjectProvider<>() {
            @Override
            public SudoGate getObject() {
                if (gate == null) {
                    throw new UnsupportedOperationException("no sudo gate in this context");
                }
                return gate;
            }

            @Override
            public SudoGate getIfAvailable() {
                return gate;
            }
        };
    }
}
