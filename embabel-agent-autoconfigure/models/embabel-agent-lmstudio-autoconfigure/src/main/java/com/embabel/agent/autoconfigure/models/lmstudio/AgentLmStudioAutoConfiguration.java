package com.embabel.agent.autoconfigure.models.lmstudio;

import com.embabel.agent.config.models.lmstudio.LmStudioModelsConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Import;

/**
 * Autoconfiguration for LM Studio AI models in the Embabel Agent system.
 * <p>
 * This class serves as a Spring Boot autoconfiguration entry point that:
 * - Scans for configuration properties in the "com.embabel.agent" package
 * - Imports the [LmStudio] configuration to register model beans
 * <p>
 * The configuration is automatically activated
 */
@AutoConfiguration
@AutoConfigureBefore(name = {"com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration"})
@Import(LmStudioModelsConfig.class)
public class AgentLmStudioAutoConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(AgentLmStudioAutoConfiguration.class);

    @PostConstruct
    public void logEvent() {
        logger.info("AgentLmStudioAutoConfiguration about to proceed...");
    }
}
