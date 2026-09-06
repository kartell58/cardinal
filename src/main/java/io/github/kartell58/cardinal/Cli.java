package io.github.kartell58.cardinal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Command-line interface: parses argv, collects the .class inputs and renders their Java source. */
final class Cli {

    private static final String USAGE = """
            cardinal - Java bytecode decompiler

            usage: cardinal [options] <class-file|directory> ...

            Decompiles each .class file given, or every .class found under each directory.
            Source is written to standard output unless --output is given.

            options:
              -o, --output <dir>   write decompiled sources under <dir>, mirroring the
                                   package layout (e.g. <dir>/sample/Demo.java)
              -h, --help           show this help and exit
            """;

    /** Parses {@code args} and runs the decompiler. Returns a process exit code. */
    int run(String[] args) {
        String outDir = null;
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--help" -> {
                    System.out.print(USAGE);
                    return 0;
                }
                case "-o", "--output" -> {
                    if (i + 1 >= args.length) return error("option " + args[i] + " requires a directory argument");
                    outDir = args[++i];
                }
                default -> inputs.add(args[i]);
            }
        }
        if (inputs.isEmpty()) return error("no inputs given");
        if (outDir != null) {
            try {
                Files.createDirectories(Path.of(outDir));
            } catch (IOException e) {
                return error(outDir + ": " + e.getMessage());
            }
        }

        int rc = 0;
        Map<Path, Map<String, ClassFile>> siblingCache = new HashMap<>();
        for (String in : inputs) {
            List<Path> files = collect(Path.of(in));
            if (files.isEmpty()) rc = error("no .class files under " + in);
            for (Path f : files) {
                try {
                    ClassFile cf = ClassFile.read(Files.readAllBytes(f));
                    String src = Writer.write(cf, siblingsFor(f, siblingCache));
                    if (outDir != null) {
                        Path target = outPath(Path.of(outDir), cf.name);
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, src.endsWith("\n") ? src : src + "\n");
                    } else {
                        System.out.print(src);
                        System.out.println();
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("cardinal: " + f + ": " + e.getMessage());
                    e.printStackTrace(System.err);
                    rc = 1;
                }
            }
        }
        return rc;
    }

    /** All class files in the directory holding {@code f}, keyed by internal name, for sibling resolution. */
    private static Map<String, ClassFile> siblingsFor(Path f, Map<Path, Map<String, ClassFile>> cache) {
        Path dir = f.toAbsolutePath().getParent();
        Map<String, ClassFile> hit = cache.get(dir);
        if (hit != null) return hit;
        Map<String, ClassFile> out = new HashMap<>();
        if (dir != null) {
            try (var s = Files.walk(dir)) {
                s.filter(x -> x.toString().endsWith(".class")).sorted(Comparator.comparing(Path::toString)).forEach(x -> {
                    try {
                        ClassFile c = ClassFile.read(Files.readAllBytes(x));
                        out.put(c.name, c);
                    } catch (IOException | RuntimeException ignored) {}
                });
            } catch (IOException ignored) {}
        }
        cache.put(dir, out);
        return out;
    }

    private static Path outPath(Path root, String internalName) {
        String s = internalName.replace('.', '/');
        int slash = s.lastIndexOf('/');
        String pkg = slash < 0 ? "" : s.substring(0, slash);
        return root.resolve(pkg).resolve(s.substring(slash + 1) + ".java");
    }

    private static List<Path> collect(Path p) {
        List<Path> out = new ArrayList<>();
        if (Files.isDirectory(p)) {
            try (var s = Files.walk(p)) {
                s.filter(x -> x.toString().endsWith(".class")).sorted(Comparator.comparing(Path::toString)).forEach(out::add);
            } catch (IOException ignored) {}
        } else if (p.toString().endsWith(".class")) {
            out.add(p);
        }
        return out;
    }

    private static int error(String msg) {
        System.err.println("cardinal: " + msg);
        System.err.println("try 'cardinal --help' for usage");
        return 2;
    }
}