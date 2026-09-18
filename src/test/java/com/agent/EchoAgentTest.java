package com.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Lab 01 的行为回归测试，沿用原有的五个用例。 */
class EchoAgentTest {
    private final EchoAgent agent = new EchoAgent();

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
