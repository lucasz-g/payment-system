package br.com.garcia.payment.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity 
public class WebSecurityConfig {

    @Bean 
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
        .cors(cors -> cors.disable()).csrf(csrf -> csrf.disable())
        .authorizeHttpRequests((requests) -> requests.anyRequest().permitAll()); 
        
        return http.build();  
    }
}
