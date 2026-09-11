package com.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTest {
    private final Agent agent = new Agent();

    @Test
    void shouldGreetOnHello() {
        assertEquals("Hello, Agent!", agent.respond("hello"));
    }

    @Test
    void shouldGreetOnCaseInsensitiveTrimmedHello() {
        assertEquals("Hello, Agent!", agent.respond("  HeLLo  "));
    }

    @Test
    void shouldEchoOtherInput() {
        assertEquals("I heard: hi", agent.respond("hi"));
    }

    @Test
    void shouldGreetOnBlankInput() {
        assertEquals("Hello, Agent!", agent.respond("   "));
    }

    @Test
    void shouldGreetOnNullInput() {
        assertEquals("Hello, Agent!", agent.respond(null));
    }
}
