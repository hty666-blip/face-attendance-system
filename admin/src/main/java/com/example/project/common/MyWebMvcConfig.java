package com.example.project.common;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;

/**
 * web配置
 */
@Configuration
public class MyWebMvcConfig extends WebMvcConfigurationSupport {
    @javax.annotation.Resource private com.example.project.security.AuthorizationInterceptor authorizationInterceptor;
    @Override
    protected void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 手动 MVC 配置没有沿用 Boot 的日期输出默认值；新日结果和配置统一使用ISO字符串。
        for(HttpMessageConverter<?> converter:converters) {
            if(converter instanceof MappingJackson2HttpMessageConverter)
                ((MappingJackson2HttpMessageConverter)converter).getObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
    }
    @Override
    protected void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authorizationInterceptor).addPathPatterns("/**")
                .excludePathPatterns("/swagger**/**", "/webjars/**", "/v3/**", "/doc.html", "/favicon.ico");
    }

    @Override
    protected void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.
                addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/springfox-swagger-ui/")
                .resourceChain(false);
    }

    @Override
    protected void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/swagger-ui/", "/swagger-ui/index.html");
    }
}
