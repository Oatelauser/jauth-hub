package io.github.oatelauser.jauth.app.web;

import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.CsrfPayload;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.springplus.security.annotation.RequiresRole;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理页 JSON 状态面（v1.5 B1a，路径族规约：页面路径加 {@code /api} 前缀）：{@code GET
 * /api/admin/users} 与 SSR 页（{@link AdminUsersController#page}）同口径——分页参数 page/size（默认 20
 * 上限 100）clamp 复用 {@link AdminUsersController#pageWindow}，行装配复用 {@link AdminUsersController#summary}
 * （与建号响应同形）；分页元数据直接套家族 PageResponse 形状（item/total/pageNum/pageSize/totalPage，
 * v1.3 D5 老账⑦口径）。门语义照 SSR 页：{@code @RequiresRole(SUPER_ADMIN)}（08 票 GrantedAuthority 路线，
 * 已认证非超管 403、未认证 401 由壳层 default 链）；POST /api/admin/users（建号）不动。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, item, total, pageNum, pageSize, totalPage, csrfToken,
 * csrfHeaderName }}。组件扫描注册（app 模块 @Controller 同形态）。
 *
 * @author oatelauser
 */
@RestController
public class AdminUsersStateController {

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    public AdminUsersStateController(
            UserRepository userRepository, EducationalFlag educational, ResponseRenderer responseRenderer) {
        this.userRepository = userRepository;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 用户管理页状态（当页行 + 分页元数据）。
     *
     * @param page 页码（1 起，出界 clamp 到 [1,totalPage]，同 SSR 页）
     * @param size 页大小（clamp 到 [1,100]，同 SSR 页）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态（item 为当页用户摘要行）
     */
    @RequiresRole(role = RequiresRole.ROLE_SUPER_ADMIN)
    @GetMapping(value = "/api/admin/users", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object state(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = AdminUsersController.USERS_PAGE_DEFAULT_SIZE) int size,
            HttpServletRequest request) {
        long total = this.userRepository.countAll();
        AdminUsersController.PageWindow window = AdminUsersController.pageWindow(page, size, total);
        List<Map<String, Object>> item =
                summaries(this.userRepository.findPage((window.pageNum() - 1) * window.pageSize(), window.pageSize()));
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new AdminUsersState(
                this.educational.enabled(),
                item,
                total,
                window.pageNum(),
                window.pageSize(),
                window.totalPage(),
                csrf.csrfToken(),
                csrf.csrfHeaderName()));
    }

    /** 当页行装配（AdminUsersController.summary 与建号响应同形，无凭据材料）。 */
    private static List<Map<String, Object>> summaries(List<JauthUser> users) {
        List<Map<String, Object>> rows = new ArrayList<>(users.size());
        for (JauthUser user : users) {
            rows.add(AdminUsersController.summary(user));
        }
        return rows;
    }

    /** 用户管理页状态载荷（分页字段名即家族 PageResponse 契约：item/total/pageNum/pageSize/totalPage）。 */
    public record AdminUsersState(
            boolean educational,
            List<Map<String, Object>> item,
            long total,
            int pageNum,
            int pageSize,
            int totalPage,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {

        /** item 防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变）。 */
        public AdminUsersState {
            item = item == null ? List.of() : List.copyOf(item);
        }
    }
}
