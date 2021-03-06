package com.jakduk.api.configuration.security;

import com.jakduk.api.configuration.JakdukProperties;
import com.jakduk.api.configuration.security.handler.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import javax.annotation.Resource;

/**
 * @author pyohwan
 * 16. 4. 6 오후 9:51
 */

@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Resource private JakdukProperties jakdukProperties;

    @Autowired private SnsAuthenticationProvider snsAuthenticationProvider;
    @Autowired private UserDetailsService userDetailsService;

    @Override
    public void configure(WebSecurity web) throws Exception {
        web.ignoring().antMatchers("/resources/**");
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {

        http
                // we don't need CSRF because our token is invulnerable
                .csrf().disable()

                .formLogin()
                    .loginProcessingUrl("/api/auth/login")
                    .successHandler(new RestJakdukSuccessHandler())
                    .failureHandler(new RestJakdukFailureHandler())

                .and().rememberMe()
                    .tokenValiditySeconds(jakdukProperties.getRememberMeExpiration())

                .and().logout()
                    .logoutSuccessHandler(new RestLogoutSuccessHandler())
                    .logoutRequestMatcher(new AntPathRequestMatcher("/api/auth/logout"))
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")

                .and().exceptionHandling()
                    .authenticationEntryPoint(new RestAuthenticationEntryPoint())
                    .accessDeniedHandler(new RestAccessDeniedHandler())

                //Configures url based authorization
                .and().authorizeRequests()
                    .regexMatchers(
                            HttpMethod.POST,
                            "/api/board/[a-z]+",                      // 글 쓰기
                            "/api/board/[a-z]+/\\d+/comment",                       // 댓글 달기
                            "/api/board/[a-z]+/\\d+/like|dislike",                  // 글 감정 표현
                            "/api/board/[a-z]+/comment/[\\da-z]+/like|dislike"      // 댓글 감정 표현
                            ).hasAnyAuthority(
                            JakdukAuthority.ROLE_USER_01.name(), JakdukAuthority.ROLE_USER_02.name(), JakdukAuthority.ROLE_USER_03.name())

                    .antMatchers(
                            HttpMethod.POST,
                            "/api/auth/user",                            // 이메일 기반 회원 가입
                            "/api/auth/user/*"                      // SNS 기반 회원 가입 및 SNS 임시 프로필 조회
                    ).anonymous()
                    .antMatchers(
                            HttpMethod.GET,
                            "/api/auth/login",                  // 로그인
                            "/api/auth/login/*",                    // SNS 로그인
                            "/api/user/exist/email/anonymous",      // 비 로그인 상태에서 특정 user Id를 제외하고 Email 중복 검사
                            "/api/user/exist/username/anonymous"    // 비 로그인 상태에서 특정 user Id를 제외하고 별명 중복 검사
                    ).anonymous()

                    .anyRequest().permitAll();

    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.authenticationProvider(snsAuthenticationProvider);
        auth.userDetailsService(userDetailsService).passwordEncoder(passwordEncoder());
    }

    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }

    @Bean
    public StandardPasswordEncoder passwordEncoder() {
        return new StandardPasswordEncoder();
    }

}
