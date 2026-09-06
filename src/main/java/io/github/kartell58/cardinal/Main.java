package io.github.kartell58.cardinal;

/** Entry point: argument handling lives in {@link Cli}. */
public final class Main {

    public static void main(String[] args) {
        System.exit(new Cli().run(args));
    }
}