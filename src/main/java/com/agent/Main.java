package com.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Command-line entry point. It connects standard input/output to {@link Agent}.
 */
public class Main {
    public static void main(String[] args) throws IOException {
        Agent agent = new Agent();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            if ("quit".equalsIgnoreCase(line.trim())) {
                break;
            }
            System.out.println(agent.respond(line));
        }
    }
}
