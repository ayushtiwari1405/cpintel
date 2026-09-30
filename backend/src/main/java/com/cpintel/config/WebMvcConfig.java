package com.cpintel.config;

import com.cpintel.classrooms.ClassroomAccessInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ClassroomAccessInterceptor classroomAccess;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(classroomAccess).addPathPatterns("/api/v1/admin/**");
    }
}
