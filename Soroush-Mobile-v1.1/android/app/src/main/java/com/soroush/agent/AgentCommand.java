package com.soroush.agent;

public final class AgentCommand {
    public enum Type { REQUEST_RIDE, CANCEL_RIDE, PAY_WALLET }
    public final Type type;
    public final String origin;
    public final String destination;
    public AgentCommand(Type type, String origin, String destination) {
        this.type=type; this.origin=origin; this.destination=destination;
    }
}
