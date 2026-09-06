package uy.pensiones.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import uy.pensiones.security.PensionMediaAccessInterceptor;
import uy.pensiones.storage.MediaStorage;


@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PensionMediaAccessInterceptor mediaAccess;
    private final MediaStorage mediaStorage;

    public WebConfig(PensionMediaAccessInterceptor mediaAccess, MediaStorage mediaStorage) {
        this.mediaAccess = mediaAccess;
        this.mediaStorage = mediaStorage;
    }


    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(mediaAccess).addPathPatterns("/media/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        mediaStorage.resourceLocation().ifPresent(location ->
                registry.addResourceHandler("/media/**").addResourceLocations(location));
    }
}
