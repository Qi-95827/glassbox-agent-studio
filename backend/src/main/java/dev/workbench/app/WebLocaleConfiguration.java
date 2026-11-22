package dev.workbench.app;

import dev.workbench.framework.i18n.LocalizedMessages;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/** 启用 Accept-Language 协商：仅支持 zh-CN 与 en-US，缺省回退中文。 */
@Configuration
public class WebLocaleConfiguration implements WebMvcConfigurer {
  public WebLocaleConfiguration(MessageSource messageSource) {
    LocalizedMessages.configure(messageSource);
  }

  @Bean
  LocaleResolver localeResolver() {
    AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
    resolver.setSupportedLocales(List.of(Locale.SIMPLIFIED_CHINESE, Locale.US));
    resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
    return resolver;
  }
}
