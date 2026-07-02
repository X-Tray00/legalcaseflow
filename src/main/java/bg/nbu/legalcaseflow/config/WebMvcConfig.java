package bg.nbu.legalcaseflow.config;

import bg.nbu.legalcaseflow.websocket.AppChangeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AppChangeInterceptor appChangeInterceptor;

    public WebMvcConfig(AppChangeInterceptor appChangeInterceptor) {
        this.appChangeInterceptor = appChangeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(appChangeInterceptor).addPathPatterns("/api/**");
    }
}
