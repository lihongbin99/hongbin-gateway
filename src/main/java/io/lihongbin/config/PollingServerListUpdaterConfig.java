package io.lihongbin.config;

import com.netflix.loadbalancer.PollingServerListUpdater;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PollingServerListUpdaterConfig {

    @Bean
    public PollingServerListUpdater pollingServerListUpdater() {
        return new PollingServerListUpdater(1000, 500);
    }

}
