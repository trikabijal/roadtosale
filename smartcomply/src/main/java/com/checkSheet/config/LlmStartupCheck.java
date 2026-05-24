package com.checkSheet.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup check for the Trika LLM module.
 * Only active when ai.photo-assessment.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "ai.photo-assessment.enabled", havingValue = "true")
public class LlmStartupCheck {

    private static final Logger logger = LoggerFactory.getLogger(LlmStartupCheck.class);

    @EventListener(ApplicationReadyEvent.class)
    public void checkLlmAvailability() {
        // Check if the LLM module JAR is on the classpath
        try {
            Class.forName("com.trika.llm.LlmClient");
            logger.info("LLM module loaded (com.trika:llm)");
        } catch (ClassNotFoundException e) {
            logger.error("========================================");
            logger.error("LLM MODULE NOT FOUND ON CLASSPATH");
            logger.error("AI photo assessment will not work.");
            logger.error("Add to pom.xml:");
            logger.error("  <dependency>");
            logger.error("    <groupId>com.trika</groupId>");
            logger.error("    <artifactId>llm</artifactId>");
            logger.error("    <version>1.0.0</version>");
            logger.error("  </dependency>");
            logger.error("========================================");
            return;
        }

        // Check if at least one provider key is configured
        String anthropicKey = System.getenv("ANTHROPIC_KEY");
        String openaiKey = System.getenv("OPENAI_KEY");
        String geminiKey = System.getenv("GEMINI_KEY");
        String grokKey = System.getenv("GROK_KEY");

        boolean anyConfigured = (anthropicKey != null && !anthropicKey.isBlank())
                || (openaiKey != null && !openaiKey.isBlank())
                || (geminiKey != null && !geminiKey.isBlank())
                || (grokKey != null && !grokKey.isBlank());

        if (!anyConfigured) {
            logger.warn("========================================");
            logger.warn("NO LLM PROVIDER API KEYS CONFIGURED");
            logger.warn("AI photo assessment will fail at runtime.");
            logger.warn("Set at least one of: ANTHROPIC_KEY, OPENAI_KEY, GEMINI_KEY, GROK_KEY");
            logger.warn("========================================");
        } else {
            StringBuilder providers = new StringBuilder();
            if (anthropicKey != null && !anthropicKey.isBlank()) providers.append("Anthropic ");
            if (openaiKey != null && !openaiKey.isBlank()) providers.append("OpenAI ");
            if (geminiKey != null && !geminiKey.isBlank()) providers.append("Gemini ");
            if (grokKey != null && !grokKey.isBlank()) providers.append("Grok ");
            logger.info("LLM providers configured: {}", providers.toString().trim());
        }
    }
}
