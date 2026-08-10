package com.fpt.swp.config;

import com.fpt.swp.security.CustomAccessDeniedHandler;
import com.fpt.swp.security.CustomAuthenticationEntryPoint;
import com.fpt.swp.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          CustomAuthenticationEntryPoint authenticationEntryPoint,
                          CustomAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> {
                    // ⚠️ Explicitly handle OPTIONS for CORS preflight BEFORE anything else
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();

                    auth.requestMatchers(
                            "/",
                            "/v3/api-docs/**",
                            "/swagger-ui/**",
                            "/swagger-ui.html"
                    ).permitAll();

                    auth.requestMatchers("/api/auth/**", "/error").permitAll();
                    auth.requestMatchers("/api/dashboard/public", "/api/trends/**").permitAll();
                    // Pricing is public; subscribe/payment/quota fall through to authenticated
                    auth.requestMatchers(HttpMethod.GET, "/api/plans").permitAll();
                    // Public read access to papers/journals/authors/keywords (GET only)
                    auth.requestMatchers(HttpMethod.GET, "/api/papers/**", "/api/journals/**", "/api/authors/**", "/api/keywords/**", "/api/topics/**", "/api/top-papers").permitAll();
                    // Paper upload requires authentication (role checked via @PreAuthorize)
                    auth.requestMatchers(HttpMethod.POST, "/api/papers/upload").authenticated();
                    // Moderation requires authentication (role checked via @PreAuthorize)
                    auth.requestMatchers("/api/moderation/**").authenticated();
                    // AI endpoints: ALL require login — quota is enforced per user (FREE 3/24h,
                    // PRO 50/24h). No anonymous access so usage can't be spoofed via IP.
                    auth.requestMatchers("/api/ai/**").authenticated();
                    // VNPay callbacks are called by VNPay/the browser WITHOUT a JWT — must be public.
                    // Security here comes from HMAC-SHA512 signature verification, not from auth.
                    auth.requestMatchers(HttpMethod.GET,
                            "/api/payments/vnpay/return", "/api/payments/vnpay/ipn").permitAll();
                    auth.anyRequest().authenticated();
                });

        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Arrays.asList(
                "http://localhost:*",
                "https://trend-searchor-fe.vercel.app",
                "https://trend-searchor-fe-*.vercel.app",
                "https://trendsearchor.vercel.app",
                "https://trendsearchor-*.vercel.app",
                "https://trendsearchor-be-production.up.railway.app"
        ));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization", "Content-Type", "Accept", "X-Requested-With", "Origin"
        ));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
