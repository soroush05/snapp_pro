package com.soroush.agent;

/** Structured terminal/provider events. UI state must never depend on parsing Persian status text. */
public enum SnappEvent {
    INFO,
    RIDE_REQUEST_CONFIRMED,
    RIDE_CANCEL_SENT,
    PAYMENT_SENT,
    SAFE_FAILURE
}
