package com.example.project.assistant;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import javax.annotation.Resource;
@Component
public class AssistantRecovery implements ApplicationRunner {
    @Resource private AssistantService assistant;
    @Value("${assistant.enabled:false}") private boolean enabled;
    @Override public void run(ApplicationArguments args){if(enabled)assistant.recoverExpired();}
}
