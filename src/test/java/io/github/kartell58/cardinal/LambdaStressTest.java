package io.github.kartell58.cardinal;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lambdas, method references, anonymous/nested classes and generics against lambda-heavy fixtures. */
class LambdaStressTest {

    private static final String[] PLACEHOLDERS = {
            "// invokedynamic (unhandled)", "// anonymous class (unsupported)",
            "// nested type not decompiled", ": implementation not found",
    };

    @Test
    void noPlaceholders() throws Exception {
        String src = decompile(fixture("Stress.java"), false);
        for (String p : PLACEHOLDERS) assertFalse(src.contains(p), "found placeholder " + p);
    }

    @Test
    void methodReferences() throws Exception {
        String src = decompile(fixture("Stress.java"), false);
        assertTrue(src.contains("String::toUpperCase"), src);
        assertTrue(src.contains("String::toLowerCase"), src);
        assertTrue(src.contains("StringBuilder::append"), src);
    }

    @Test
    void functionalVariableKeepsGenerics() throws Exception {
        String src = decompile(fixture("Stress.java"), false);
        assertTrue(src.contains("Function<String, String>"), src);
    }

    @Test
    void concatInsideLambda() throws Exception {
        String src = decompile(fixture("Stress.java"), false);
        assertTrue(src.contains("\"\"+p0+p1"), src);
    }

    @Test
    void blockLambdaAndCapture() throws Exception {
        String src = decompile(fixture("Stress.java"), false);
        assertTrue(src.contains("(String p0, String p1) -> {"), () -> "block lambda head: " + src);
        assertTrue(src.contains("Integer.compare("), src);
        assertFalse(src.contains("\u0001"), "captures must be substituted, not left as placeholders");
    }

    @Test
    void collectionConstructorUsesDiamond() throws Exception {
        String src = decompile(fixture("Stress.java"), false);
        assertTrue(src.contains("java.util.ArrayList<>("), src);
    }

    @Test
    void nestedGenericClassRendered() throws Exception {
        String src = decompile(fixture("Stress.java"), false);
        assertTrue(src.contains("class Pair<T> implements Op<T>"), src);
        assertTrue(src.contains("public T apply(T arg0, T arg1)"), src);
    }

    @Test
    void anonymousClassInline() throws Exception {
        String src = decompile(fixture("Stress.java"), false);
        assertTrue(src.contains("new Op<java.lang.String>() {"), src);
        assertFalse(src.contains("class Stress$"), "anonymous/nested types must be inlined, never re-declared");
    }

    @Test
    void rebuiltBehavesLikeOriginal() throws Exception {
        Path outDir = compile(fixture("Stress.java"), false);
        Path deDir = rebuild(outDir, "stress/Stress", false);
        try (URLClassLoader original = loader(outDir); URLClassLoader rebuilt = loader(deDir.resolve("out"))) {
            assertEquals(runMain(original, "stress.Stress"), runMain(rebuilt, "stress.Stress"));
        }
    }

    @Test
    void decompileStressSourceCompiles() throws Exception {
        Path outDir = compile(fixture("DecompileStress.java"), true);
        Path deDir = rebuild(outDir, "DecompileStress", true);
        String src = Files.readString(deDir.resolve("DecompileStress.java"));
        for (String p : PLACEHOLDERS) assertFalse(src.contains(p), "found placeholder " + p);
        assertTrue(src.contains("String::toUpperCase"), src);
        assertTrue(src.contains("reduce(\"\""), src);
    }

    // ===== helpers =====

    private static String fixture(String name) throws IOException {
        try (var in = LambdaStressTest.class.getResourceAsStream("/sample/stress/" + name)) {
            Objects.requireNonNull(in, "fixture /sample/stress/" + name + " missing");
            return new String(in.readAllBytes());
        }
    }

    private static Path compile(String src, boolean noDebug) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("public\\s+(?:final\\s+)?(?:class|interface)\\s+(\\w+)").matcher(src);
        assertTrue(m.find(), "fixture must declare a public class");
        Path dir = Files.createTempDirectory("cardinal-lambda");
        Path srcFile = dir.resolve(m.group(1) + ".java");
        Files.writeString(srcFile, src);
        Path out = dir.resolve("out");
        Files.createDirectories(out);
        javac(noDebug ? new String[]{"-g:none", "-d", out.toString(), srcFile.toString()}
                : new String[]{"-d", out.toString(), srcFile.toString()});
        return out;
    }

    /** Decompiles {@code name} (top-most class in {@code out}), writes it under {@code de} and recompiles
     * it to {@code de/out}. Returns {@code de} (the decompiled source directory). */
    private static Path rebuild(Path out, String name, boolean noDebug) throws Exception {
        Path target = null;
        Map<String, ClassFile> siblings = new HashMap<>();
        try (var s = Files.walk(out)) {
            for (Path f : s.filter(x -> x.toString().endsWith(".class")).toList()) {
                ClassFile c = ClassFile.read(Files.readAllBytes(f));
                if (c.name.equals(name)) target = f;
                siblings.put(c.name, c);
            }
        }
        Objects.requireNonNull(target, name + ".class not compiled");
        String source = Writer.write(ClassFile.read(Files.readAllBytes(target)), siblings);
        Path de = out.getParent().resolve("de");
        Files.createDirectories(de);
        Path deJava = de.resolve(name + ".java");
        if (deJava.getParent() != null) Files.createDirectories(deJava.getParent());
        Files.writeString(deJava, source);
        Path deOut = de.resolve("out");
        Files.createDirectories(deOut);
        javac(new String[]{"-d", deOut.toString(), deJava.toString()});
        return de;
    }

    private static String decompile(String src, boolean noDebug) throws Exception {
        Path out = compile(src, noDebug);
        ClassFile top = null;
        Map<String, ClassFile> siblings = new HashMap<>();
        try (var s = Files.walk(out)) {
            for (Path f : s.filter(x -> x.toString().endsWith(".class")).toList()) {
                ClassFile c = ClassFile.read(Files.readAllBytes(f));
                if (c.name.indexOf('$') < 0 && (top == null || top.name.length() < c.name.length())) top = c;
                siblings.put(c.name, c);
            }
        }
        Objects.requireNonNull(top, "no top-level class compiled");
        return Writer.write(top, siblings);
    }

    private static String runMain(URLClassLoader loader, String cls) throws Exception {
        Class<?> c = loader.loadClass(cls);
        Method m = c.getMethod("main", String[].class);
        String[] args = new String[0];
        var out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.out;
        System.setOut(new java.io.PrintStream(out));
        try {
            m.invoke(null, (Object) args);
        } finally {
            System.setOut(old);
        }
        return out.toString();
    }

    private static URLClassLoader loader(Path dir) throws Exception {
        return new URLClassLoader(new URL[]{dir.toUri().toURL()}, LambdaStressTest.class.getClassLoader());
    }

    private static void javac(String... args) throws Exception {
        JavaCompiler c = ToolProvider.getSystemJavaCompiler();
        Objects.requireNonNull(c, "toolchain javac missing");
        var out = new java.io.ByteArrayOutputStream();
        assertEquals(0, c.run(null, null, out, args),
                "javac " + String.join(" ", args) + " failed:\n" + out);
    }
}