package com.lrj.workflow.server.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 安全过滤链,按 {@code workflow.security.enabled} 二选一装配(恰有一个 SecurityFilterChain 生效,
 * 从而顶掉 Spring Security 默认全局保护):
 * <ul>
 *   <li>enabled=true → OAuth2 Resource Server 校验 Casdoor JWT;/actuator 探针放行,其余需认证。</li>
 *   <li>enabled=false(默认/缺省) → 全放行,保 dev/shadow 联调(明文 X-Workflow-Tenant 头路径)。</li>
 * </ul>
 * JWT 的 groups claim 归一化(去路径段/<org>_ 前缀、大写)后作为权限,与前端 normalizeGroup / BPMN candidateGroups 对齐。
 */
@Configuration
@EnableConfigurationProperties(WorkflowSecurityProperties.class)
public class SecurityConfig {

    private final WorkflowSecurityProperties props;

    public SecurityConfig(WorkflowSecurityProperties props) {
        this.props = props;
    }

    /** 生产:JWT 保护。 */
    @Bean
    @ConditionalOnProperty(prefix = "workflow.security", name = "enabled", havingValue = "true")
    SecurityFilterChain securedChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/admin/**", "/api/v1/dlq/**").hasAuthority("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthConverter())));
        return http.build();
    }

    /** dev/shadow:全放行(明文头路径)。 */
    @Bean
    @ConditionalOnProperty(prefix = "workflow.security", name = "enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** 仅 enabled 时构建解码器:优先 jwks(lazy,不阻塞启动),否则 issuer(启动拉 openid-config)。 */
    @Bean
    @ConditionalOnProperty(prefix = "workflow.security", name = "enabled", havingValue = "true")
    JwtDecoder jwtDecoder() {
        if (StringUtils.hasText(props.getJwkSetUri())) {
            return NimbusJwtDecoder.withJwkSetUri(props.getJwkSetUri()).build();
        }
        if (StringUtils.hasText(props.getIssuerUri())) {
            return JwtDecoders.fromIssuerLocation(props.getIssuerUri());
        }
        throw new IllegalStateException("workflow.security.enabled=true 需配置 workflow.security.jwk-set-uri 或 issuer-uri");
    }

    private JwtAuthenticationConverter jwtAuthConverter() {
        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(this::authoritiesFromGroups);
        return conv;
    }

    private Collection<GrantedAuthority> authoritiesFromGroups(Jwt jwt) {
        Object raw = jwt.getClaim(props.getGroupsClaim());
        if (!(raw instanceof Collection<?> groups)) {
            return List.of();
        }
        return groups.stream()
                .map(String::valueOf)
                .map(SecurityConfig::normalizeGroup)
                .filter(s -> !s.isBlank())
                .distinct()
                .map(g -> (GrantedAuthority) new SimpleGrantedAuthority(g))
                .toList();
    }

    /** 归一化组名:取路径末段、去 <org>_ 前缀、大写。与前端 normalizeGroup / BPMN candidateGroups 一致。 */
    static String normalizeGroup(String g) {
        String afterSlash = g.substring(g.lastIndexOf('/') + 1);
        String afterUnderscore = afterSlash.substring(afterSlash.lastIndexOf('_') + 1);
        return afterUnderscore.toUpperCase(Locale.ROOT);
    }
}
