package com.soroush.agent;

/** Hybrid NLU pipeline: reliable local semantic classifier first, optional stronger fallback only for uncertainty. */
public final class SemanticPipeline {
    private final SemanticRouter local;
    private final SemanticFallbackProvider fallback;
    public SemanticPipeline(){this(new SemanticRouter(),null);}
    public SemanticPipeline(SemanticRouter local,SemanticFallbackProvider fallback){this.local=local==null?new SemanticRouter():local;this.fallback=fallback;}
    public IntentEnvelope understand(String raw,ConversationContext ctx){
        IntentEnvelope e=local.understand(raw,ctx);
        if((e.decision==IntentEnvelope.Decision.UNKNOWN||e.decision==IntentEnvelope.Decision.CLARIFY)&&fallback!=null&&fallback.isAvailable()){
            try{IntentEnvelope remote=fallback.understand(raw,ctx);if(remote!=null&&remote.decision!=IntentEnvelope.Decision.UNKNOWN)return remote;}catch(Exception ignored){}
        }
        return e;
    }
}
