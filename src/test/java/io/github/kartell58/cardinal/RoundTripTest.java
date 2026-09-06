package io.github.kartell58.cardinal;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end round trip: Demo.java -> Demo.class -> decompiled source -> compiles and behaves identically. */
class RoundTripTest {

    @Test
    void demoRoundTrip() throws Exception {
        Path temp = Files.createTempDirectory("cardinal-rt");
        Path srcDir = temp.resolve("src");
        Path outDir = temp.resolve("out");
        Path deDir = temp.resolve("de");
        Path deOut = temp.resolve("deout");
        Files.createDirectories(srcDir);
        Files.createDirectories(outDir);
        Files.createDirectories(deDir);
        Files.createDirectories(deOut);

        Path demoJava = srcDir.resolve("Demo.java");
        try (var in = RoundTripTest.class.getResourceAsStream("/sample/Demo.java")) {
            Objects.requireNonNull(in, "fixture /sample/Demo.java missing");
            Files.copy(in, demoJava);
        }
        assertEquals(0, javac("-g", "-d", outDir.toString(), demoJava.toString()));

        ClassFile cf = ClassFile.read(Files.readAllBytes(outDir.resolve("sample/Demo.class")));
        String source = Writer.write(cf);
        assertTrue(source.contains("while (i<arg0)"), "loop condition should render inverted cleanly");

        Path deJava = deDir.resolve("Demo.java");
        Files.writeString(deJava, source);
        assertEquals(0, javac("-d", deOut.toString(), deJava.toString()),
                () -> "decompiled source must compile:\n" + source);

        try (URLClassLoader original = loader(outDir); URLClassLoader rebuilt = loader(deOut)) {
            Class<?> od = original.loadClass("sample.Demo");
            Class<?> rd = rebuilt.loadClass("sample.Demo");
            assertSameResults(od, rd);
        }
    }

    private static URLClassLoader loader(Path dir) throws Exception {
        return new URLClassLoader(new URL[]{dir.toUri().toURL()}, RoundTripTest.class.getClassLoader());
    }

    private static void assertSameResults(Class<?> a, Class<?> b) throws Exception {
        Constructor<?> ca = a.getConstructor(int.class);
        Constructor<?> cb = b.getConstructor(int.class);
        Object oa = ca.newInstance(5);
        Object ob = cb.newInstance(5);

        Object[][] invokes = {
                {"add", new Class[]{int.class}, new Object[]{3}},
                {"max", new Class[]{int.class, int.class}, new Object[]{3, 7}},
                {"max", new Class[]{int.class, int.class}, new Object[]{9, 2}},
                {"sumTo", new Class[]{int.class}, new Object[]{5}},
                {"describe", new Class[]{long.class}, new Object[]{50L}},
                {"describe", new Class[]{long.class}, new Object[]{200L}},
                {"describe", new Class[]{long.class}, new Object[]{-3L}},
                {"color", new Class[]{int.class}, new Object[]{2}},
                {"color", new Class[]{int.class}, new Object[]{9}},
                {"doWhile", new Class[]{int.class}, new Object[]{3}},
                {"flag", new Class[]{boolean.class, boolean.class}, new Object[]{true, false}},
                {"flag", new Class[]{boolean.class, boolean.class}, new Object[]{true, true}},
                {"flag", new Class[]{boolean.class, boolean.class}, new Object[]{false, true}},
        };
        for (Object[] inv : invokes) {
            String n = (String) inv[0];
            Method ma = a.getMethod(n, (Class<?>[]) inv[1]);
            Method mb = b.getMethod(n, (Class<?>[]) inv[1]);
            Object ra = ma.invoke(oa, (Object[]) inv[2]);
            Object rb = mb.invoke(ob, (Object[]) inv[2]);
            assertEquals(ra, rb, "method " + n);
        }
    }

    private static int javac(String... args) throws Exception {
        JavaCompiler c = ToolProvider.getSystemJavaCompiler();
        Objects.requireNonNull(c, "toolchain javac missing");
        return c.run(null, null, null, args);
    }
}