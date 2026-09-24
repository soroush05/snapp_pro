package com.soroush.agent;

/** Shared language-independent intent vocabulary for the whole agent. */
public enum SemanticIntent {
    GREETING,
    HELP,
    REQUEST_RIDE,
    CHANGE_ORIGIN,
    CHANGE_DESTINATION,
    CANCEL_FLOW,
    CANCEL_RIDE,
    ADD_SAVED_PLACE,
    EDIT_SAVED_PLACE,
    DELETE_SAVED_PLACE,
    SHOW_SAVED_PLACE,
    SEARCH_LOCATION,
    SHOW_MAP,
    USE_CURRENT_LOCATION,
    CONFIRM,
    REJECT,
    CORRECT_PREVIOUS,
    ANSWER_SLOT,
    UNKNOWN
}
