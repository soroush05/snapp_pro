package com.soroush.agent;

/** Optional stronger NLU provider. It may understand language but is never allowed to execute actions. */
public interface SemanticFallbackProvider {
    boolean isAvailable();
    IntentEnvelope understand(String raw,ConversationContext context) throws Exception;
}
