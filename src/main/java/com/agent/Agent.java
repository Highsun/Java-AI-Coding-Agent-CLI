package com.agent;

/**
 * Contains the input-to-response logic for the CLI.
 */
public class Agent {
    /**
     * Responds to a single user input without performing any I/O.
     *
     * @param input user input; may be {@code null}
     * @return the greeting for blank/hello input, or an echo for other input
     */
    public String respond(String input) {
        if (input == null || input.isBlank()) {
            return "Hello, Agent!";
        }
        if ("hello".equalsIgnoreCase(input.trim())) {
            return "Hello, Agent!";
        }
        return "I heard: " + input;
    }
}
