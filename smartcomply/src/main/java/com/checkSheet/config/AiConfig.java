package com.checkSheet.config;

import com.trika.llm.LlmConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Activates the Trika LLM module only when ai.photo-assessment.enabled=true.
 *
 * When the property is false (or absent), this class is never loaded:
 *  - LlmConfig is not bound
 *  - com.trika.llm package is not scanned
 *  - No LlmClient, BenchmarkingLlmClient, or SpendingGuard beans are created
 *
 * To enable AI photo assessment, set in your profile properties:
 *   ai.photo-assessment.enabled=true
 * and ensure at least one provider key is set (ANTHROPIC_KEY, OPENAI_KEY, GEMINI_KEY, GROK_KEY).
 */
@Configuration
@ConditionalOnProperty(name = "ai.photo-assessment.enabled", havingValue = "true")
@EnableConfigurationProperties(LlmConfig.class)
@ComponentScan("com.trika.llm")
public class AiConfig {
}
