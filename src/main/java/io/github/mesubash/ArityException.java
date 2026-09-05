package io.github.mesubash;

// lets the one-argument commands share a check instead of repeating it
public class ArityException extends RuntimeException {

    public ArityException(String commandName) {
        super(commandName);
    }
}
