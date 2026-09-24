package com.soroush.agent;

/**
 * Compatibility adapter. The legacy keyword/phrase parser was removed in alpha4.
 * New code should use SemanticRouter and IntentEnvelope directly.
 */
@Deprecated
public final class ConversationEngine {
    private final SemanticRouter router=new SemanticRouter();
    public IntentResult parse(String raw,ConversationContext ctx){
        IntentEnvelope e=router.understand(raw,ctx);
        IntentResult.Intent i;
        switch(e.intent){
            case GREETING:i=IntentResult.Intent.GREETING;break;
            case REQUEST_RIDE:i=IntentResult.Intent.REQUEST_RIDE;break;
            case ADD_SAVED_PLACE:i=IntentResult.Intent.ADD_PLACE;break;
            case EDIT_SAVED_PLACE:i=IntentResult.Intent.EDIT_PLACE;break;
            case CHANGE_ORIGIN:i=IntentResult.Intent.CHANGE_ORIGIN;break;
            case CHANGE_DESTINATION:i=IntentResult.Intent.CHANGE_DESTINATION;break;
            case CANCEL_FLOW:i=IntentResult.Intent.CANCEL_FLOW;break;
            case CANCEL_RIDE:i=IntentResult.Intent.CANCEL_RIDE;break;
            case CONFIRM:i=IntentResult.Intent.CONFIRM;break;
            case REJECT:i=IntentResult.Intent.REJECT;break;
            case SHOW_MAP:i=IntentResult.Intent.SHOW_MAP;break;
            case ANSWER_SLOT:i=IntentResult.Intent.ANSWER;break;
            default:i=IntentResult.Intent.UNKNOWN;
        }
        String target=e.value.isEmpty()?e.target:e.value;
        return new IntentResult(i,e.origin,e.destination,target,e.confidence);
    }
}
