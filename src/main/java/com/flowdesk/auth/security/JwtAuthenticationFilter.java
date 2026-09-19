package com.flowdesk.auth.security;

import com.flowdesk.auth.domain.AuthClaims;
import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * 请求认证过滤器：把 {@code Authorization: Bearer <token>} 换成安全上下文中的身份。
 *
 * <p>顺序为：解析 Bearer → 校验 JWT 签名、有效期与 issuer → 按会话标识查询 Redis 会话
 * → 把身份与会话快照中的权限写入 {@code SecurityContext}。令牌只提供会话标识，
 * 角色与权限始终以 Redis 会话快照为准，因此撤销会话即可让权限变更立即生效。</p>
 *
 * <p>失败分流：本过滤器只确定身份，不判断是否放行。"没有凭据"、"凭据不可验证"和
 * "凭据有效但会话已不存在"都不设置身份，只在最后一种情况记下原因；是否放行由授权规则决定，
 * 受保护路径被拦下时由 {@link AuthEntryPoint} 按原因返回对应的 401 编码。这样登录、刷新、
 * 退出等匿名路径不会被残留的令牌短路，而受保护路径仍然拿不到身份。</p>
 *
 * <p>本类刻意不是 Spring Bean：Filter Bean 会被 Boot 自动注册到 Servlet 链，且顺序排在
 * 安全链之后，安全上下文的写入会被 {@code SecurityContextHolderFilter} 覆盖而失效。
 * 它只能通过 {@code HttpSecurity.addFilterBefore} 挂进过滤链。</p>
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    // 末尾的空格是有意的：认证方案名与凭据之间必须有一个空格
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final AuthSessionRepository authSessionRepository;
    private final Clock clock;

    /** 依赖由安全链配置在挂链时注入；本类不注册为 Bean，因此不参与容器扫描。 */
    public JwtAuthenticationFilter(JwtTokenService jwtTokenService,
                                   AuthSessionRepository authSessionRepository,
                                   Clock clock) {
        this.jwtTokenService = jwtTokenService;
        this.authSessionRepository = authSessionRepository;
        this.clock = clock;
    }

    /**
     * 每个请求走一次，只做一件事：能确定身份就把身份写进上下文，其余情况保持匿名。
     *
     * <p>四种情形：没有凭据、凭据不可验证、凭据有效但会话不存在 → 都不设置身份；
     * 凭据与会话都有效 → 写入身份和权限。是否放行始终由授权规则决定。</p>
     *
     * <p>注意：每个分支在返回前都必须调用 {@code chain.doFilter}，否则请求会停在本过滤器里，
     * 既没人处理也没人写响应。</p>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 头部缺失或不是 Bearer 格式时返回 null，表示本次请求没有可用凭据
        String token = bearerToken(request);
        if (token == null) {
            chain.doFilter(request, response);      // 匿名继续：受保护路径稍后会被授权规则拦下
            return;
        }

        AuthClaims claims;
        try {
            // 校验签名、有效期与 issuer；只取出用户标识与会话标识，票里没有权限
            claims = jwtTokenService.parse(token);
        } catch (ApiException ex) {
            // 票不可验证按"没有凭据"处理：不设置身份，是否放行交给授权规则。
            // 这里刻意用 catch + 放行而不是继续往外抛：过滤器抛出的异常不经过 GlobalExceptionHandler，
            // 会变成 500，而这应当是 401
            log.debug("request-auth-token-rejected path={} code={}", request.getRequestURI(), ex.code());
            chain.doFilter(request, response);
            return;
        }

        // 用票里的会话标识去 Redis 取会话快照；权限从这里读，不从票里读
        AuthSession session = authSessionRepository.findById(claims.sessionId()).orElse(null);
        if (session == null || sessionExpired(session)) {
            // 票本身是真的，但它指向的会话已经没了（退出、改密、账号停用都会删掉它）。
            // 只记下原因、不设置身份：受保护路径由入口点返回 AUTH_SESSION_INVALID，
            // 匿名路径（刷新、退出）必须照常处理，否则用户既退不掉也恢复不了会话。
            request.setAttribute(AuthEntryPoint.SESSION_INVALID_ATTRIBUTE, Boolean.TRUE);
            log.debug("request-auth-session-invalid path={} userId={}", request.getRequestURI(), claims.userId());
            chain.doFilter(request, response);
            return;
        }

        // 凭据与会话都有效：把身份和权限放进上下文，后面的授权与控制器都从这里读
        authenticate(session);
        chain.doFilter(request, response);
    }

    /** 取 Bearer 令牌；头部缺失或格式不符时返回 {@code null}，表示本次请求没有可用凭据。 */
    private static String bearerToken(HttpServletRequest request) {
        // 头字段名大小写不敏感，容器会处理 "authorization" 这类写法
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        // 切掉 "Bearer " 前缀；令牌两侧可能带空白，去掉后为空同样按"没有凭据"处理
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** 键的 TTL 与快照的过期时间可能因人工写入或时钟漂移而不一致，这里再比一次。 */
    private boolean sessionExpired(AuthSession session) {
        return !session.expiresAt().isAfter(clock.instant());
    }

    /**
     * 把会话快照变成安全上下文里的身份。之后的授权规则（{@code hasAuthority(...)}）
     * 与业务代码（{@code SecurityContextHolder.getContext().getAuthentication()}）都读这里。
     *
     * <p>授权集合只放权限编码、且保持裸码（如 {@code TICKET_CREATE}）；角色编码只留在身份对象里，
     * 不重复放进授权集合，避免同一件事有两个真相来源。</p>
     */
    private void authenticate(AuthSession session) {
        // 身份取会话快照而不是令牌：这样撤销会话就能让用户与权限变更立即生效
        AuthPrincipal principal = new AuthPrincipal(
                session.userId(), session.username(), session.displayName(), session.sessionId());
        // SimpleGrantedAuthority 是"一条权限"的包装，hasAuthority('TICKET_CREATE') 比的就是这些字符串
        List<SimpleGrantedAuthority> authorities = session.permissionCodes().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        // 三参构造表示"已认证"（两参构造表示尚未认证，那是登录表单用的场景）
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
        // 写进当前请求的上下文即可：后续授权与控制器在同一个线程里直接读它
        SecurityContextHolder.setContext(context);
    }
}
