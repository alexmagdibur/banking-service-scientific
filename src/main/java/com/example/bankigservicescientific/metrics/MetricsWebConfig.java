package com.example.bankigservicescientific.metrics;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MetricsWebConfig implements WebMvcConfigurer {

    private final OperationMetricsInterceptor interceptor;

    public MetricsWebConfig(OperationMetricsInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
