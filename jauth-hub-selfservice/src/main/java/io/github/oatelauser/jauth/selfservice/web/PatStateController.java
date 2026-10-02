package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.CsrfPayload;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.selfservice.pat.PatRecord;
import io.github.oatelauser.jauth.selfservice.pat.PatService;
import io.github.oatelauser.jauth.selfservice.web.PatController.ScopeItem;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PAT 管理页 JSON 状态面（v1.5 B1a，路径族规约：页面路径加 {@code /api} 前缀）：{@code GET
 * /api/selfservice/pat} 与 SSR 页（{@link PatController#page}）同口径——patSupported = 服务在场性
 * （memory 模式 false）、服务在场时守"主体在池"（A0503，同 SSR 页）；pats 行装配复用
 * {@link PatController#patView}（与既有 {@code /selfservice/pat/list} 同形同源），scope 目录带服务端
 * i18n 解析描述（B3 决议：唯一例外，不进前端字典）。安全边界同 SSR 页（部署方 default 链，starter 链不动）。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, patSupported, scopes, validityDays, defaultValidityDays,
 * pats, now, csrfToken, csrfHeaderName }}。scope 目录/有效期阶梯/now 恒在场（不依赖服务在场性，前端表单
 * 常驻）；pats 在服务缺席时空列表。
 *
 * @author oatelauser
 */
@RestController
public class PatStateController {

    private final @Nullable PatService patService;

    private final ScopeCatalog scopeCatalog;

    private final UserRepository userRepository;

    private final MessageSource messageSource;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    private final Clock clock;

    public PatStateController(
            @Nullable PatService patService,
            ScopeCatalog scopeCatalog,
            UserRepository userRepository,
            MessageSource messageSource,
            EducationalFlag educational,
            ResponseRenderer responseRenderer,
            Clock clock) {
        this.patService = patService;
        this.scopeCatalog = scopeCatalog;
        this.userRepository = userRepository;
        this.messageSource = messageSource;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
        this.clock = clock;
    }

    /**
     * PAT 管理页状态。
     *
     * @param principal 当前登录主体（服务在场时须在池，同 SSR 页）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/selfservice/pat", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object state(@Nullable Principal principal, HttpServletRequest request) {
        List<Map<String, Object>> pats = List.of();
        Instant now = this.clock.instant();
        if (this.patService != null) {
            JauthUser user = requireUser(principal);
            pats = patViews(this.patService.listActive(user.id()), now);
        }
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new PatState(
                this.educational.enabled(),
                this.patService != null,
                PatController.scopeItems(this.scopeCatalog, this.messageSource, LocaleContextHolder.getLocale()),
                PatController.VALIDITY_DAYS.stream().sorted().toList(),
                PatController.DEFAULT_VALIDITY_DAYS,
                pats,
                now,
                csrf.csrfToken(),
                csrf.csrfHeaderName()));
    }

    /** 与既有 /list 同形的行装配（同 now 基准，过期判定口径一致）。 */
    private static List<Map<String, Object>> patViews(List<PatRecord> records, Instant now) {
        List<Map<String, Object>> views = new ArrayList<>(records.size());
        for (PatRecord record : records) {
            views.add(PatController.patView(record, now));
        }
        return views;
    }

    /** 主体在池守卫（与 PatController 同语义）。 */
    private JauthUser requireUser(@Nullable Principal principal) {
        if (principal == null) {
            throw new JauthException(SelfServiceErrorCode.A0503);
        }
        JauthUser user = this.userRepository.findByUsername(principal.getName());
        if (user == null) {
            throw new JauthException(SelfServiceErrorCode.A0503);
        }
        return user;
    }

    /** PAT 页状态载荷（字段名即 B3 前端契约；pats 行与既有 /list 同形）。 */
    public record PatState(
            boolean educational,
            boolean patSupported,
            List<ScopeItem> scopes,
            List<Integer> validityDays,
            int defaultValidityDays,
            List<Map<String, Object>> pats,
            Instant now,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {

        /** 集合防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变）。 */
        public PatState {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            validityDays = validityDays == null ? List.of() : List.copyOf(validityDays);
            pats = pats == null ? List.of() : List.copyOf(pats);
        }
    }
}
