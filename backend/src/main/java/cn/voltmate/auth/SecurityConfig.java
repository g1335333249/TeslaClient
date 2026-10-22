package cn.voltmate.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain filter(HttpSecurity http) throws Exception {
        return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll()).build();
    }
}
