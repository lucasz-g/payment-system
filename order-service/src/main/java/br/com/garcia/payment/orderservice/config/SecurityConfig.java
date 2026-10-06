package br.com.garcia.payment.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity 
public class SecurityConfig {

    @Bean 
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests((requests) -> requests.anyRequest().permitAll()); 
        
        return http.build();  
    }

    @Bean 
    CorsConfigurationSource corsConfigurationSource() {
        // TODO: Implementar configuração de CORS
        return null; 
    }
}
