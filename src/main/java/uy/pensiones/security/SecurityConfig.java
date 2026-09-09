package uy.pensiones.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import uy.pensiones.repo.UserRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import uy.pensiones.web.error.ApiErrorWriter;
import uy.pensiones.service.BackofficeSessionService;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final CustomOidcUserService customOidcUserService;
    private final UserRepository users;
    private final ObjectMapper objectMapper;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    public SecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                          CustomOidcUserService customOidcUserService,
                          UserRepository users,
                          ObjectMapper objectMapper) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.customOidcUserService = customOidcUserService;
        this.users = users;
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ClientRegistrationRepository repo,
                                            BackofficeSessionService backofficeSessions) throws Exception {

        // Resolver que inyecta prompt=select_account a Google
        var defaultResolver =
                new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization");

        OAuth2AuthorizationRequestResolver resolver = new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                OAuth2AuthorizationRequest req = defaultResolver.resolve(request);
                return customize("google", req);
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                OAuth2AuthorizationRequest req = defaultResolver.resolve(request, clientRegistrationId);
                return customize(clientRegistrationId, req);
            }

            private OAuth2AuthorizationRequest customize(String registrationId, OAuth2AuthorizationRequest req) {
                if (req == null) return null;
                if (!"google".equals(registrationId)) return req;

                Map<String, Object> extra = new HashMap<>(req.getAdditionalParameters());
                // Para forzar selector de cuenta:
                extra.put("prompt", "select_account");
                // Si querés “forzar pantalla” más seguido, podés probar:
                // extra.put("prompt", "select_account consent");

                return OAuth2AuthorizationRequest.from(req)
                        .additionalParameters(extra)
                        .build();
            }
        };

        SimpleUrlAuthenticationSuccessHandler successHandler =
                new SimpleUrlAuthenticationSuccessHandler(frontendUrl);

        var portalCsrfRepository = new HttpSessionCsrfTokenRepository();

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(portalCsrfRepository)
                        // El Backoffice ya usa un token CSRF propio ligado a su cookie opaca.
                        // Los webhooks de proveedores son machine-to-machine y no disponen de sesión browser.
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/api/backoffice/**"),
                                new AntPathRequestMatcher("/api/admin/**"),
                                new AntPathRequestMatcher("/api/public/payment-webhooks/**")
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/error", "/public/**", "/favicon.ico",
                                "/sitemap.xml", "/robots.txt",
                                "/oauth2/**", "/login/**", "/media/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/ws/**").authenticated()

                        // IMPORTANTE: antes de /api/**
                        .requestMatchers("/api/invites/*").permitAll()
                        .requestMatchers("/api/auth/csrf").permitAll()
                        .requestMatchers("/api/invites/*/accept").authenticated()
                        .requestMatchers("/api/public/**").permitAll()
                        // Backoffice: autenticación local de empleados, separada de OAuth del marketplace.
                        .requestMatchers("/api/backoffice/auth/login").permitAll()
                        .requestMatchers("/api/backoffice/auth/**").hasAuthority("ROLE_BACKOFFICE_AUTHENTICATED")
                        .requestMatchers("/api/admin/staff/**").hasAuthority("BACKOFFICE_STAFF_MANAGE")
                        .requestMatchers("/api/admin/users/**").hasAuthority("BACKOFFICE_USER_MANAGE")
                        .requestMatchers("/api/admin/study-centers/**").hasAuthority("BACKOFFICE_CATALOG_MANAGE")
                        .requestMatchers("/api/admin/commercial/payment-settings/**").hasAuthority("BACKOFFICE_PAYMENT_CONFIG_MANAGE")
                        .requestMatchers("/api/admin/commercial/**").hasAuthority("BACKOFFICE_COMMERCIAL_MANAGE")
                        .requestMatchers("/api/admin/**").hasAuthority("ROLE_BACKOFFICE")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor((request, response, authException) ->
                                        ApiErrorWriter.write(request, response, objectMapper, HttpStatus.UNAUTHORIZED,
                                                "UNAUTHORIZED", "Debes iniciar sesión para continuar."),
                                new AntPathRequestMatcher("/api/**"))
                        .defaultAccessDeniedHandlerFor((request, response, accessDeniedException) -> {
                                    if (accessDeniedException instanceof CsrfException) {
                                        ApiErrorWriter.write(request, response, objectMapper, HttpStatus.FORBIDDEN,
                                                "CSRF_INVALID",
                                                "La validación de seguridad de la sesión falló. Actualiza la página e intenta nuevamente.");
                                        return;
                                    }
                                    ApiErrorWriter.write(request, response, objectMapper, HttpStatus.FORBIDDEN,
                                            "FORBIDDEN", "No tienes permisos para realizar esta operación.");
                                },
                                new AntPathRequestMatcher("/api/**"))
                )
                .oauth2Login(oauth -> oauth
                        .loginPage("/oauth2/authorization/google")
                        .authorizationEndpoint(a -> a.authorizationRequestResolver(resolver))
                        .userInfoEndpoint(ui -> ui
                                .userService(customOAuth2UserService)
                                .oidcUserService(customOidcUserService)
                        )
                        .successHandler(successHandler)
                )
                .addFilterAfter(new BackofficeSessionAuthenticationFilter(backofficeSessions, objectMapper), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new AccountSuspensionFilter(users, objectMapper), BackofficeSessionAuthenticationFilter.class)
                .addFilterAfter(new LegalAcceptanceFilter(users, objectMapper), AccountSuspensionFilter.class)
                .sessionManagement(session -> session
                        // Servlet 3.1+: rota el identificador al autenticar sin copiarlo a una sesión nueva.
                        .sessionFixation(fixation -> fixation.changeSessionId())
                )
                .logout(logout -> logout
                        // Con CSRF activo el cierre de sesión también debe ser una mutación autenticada.
                        .logoutRequestMatcher(new AntPathRequestMatcher("/api/auth/logout", "POST"))
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                );

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

}
