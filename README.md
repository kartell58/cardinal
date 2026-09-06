# Cardinal

Cardinal is a compact, independent Java bytecode decompiler. It reads a `.class` file
and produces idiomatic, compilable Java source.

It is not a wrapper around any existing decompiler: every parsing, control-flow,
and expression-reconstruction step is implemented from scratch in a single small
source tree (~2400 lines), keeping the tool readable end-to-end.

## Building

```bash
./gradlew clean build      # compile + run the round-trip test suite
./gradlew run --args '/path/to/Foo.class'
```

Usage
-----

```bash
java -jar cardinal.jar Foo.class            # decompile one class to stdout
java -jar cardinal.jar build/classes        # decompile every .class under a directory
java -jar cardinal.jar -o out src/          # write sources under out/ (package layout)
java -jar cardinal.jar --help               # options, incl. the -o / --output short form
```

The Gradle application entry point is `io.github.kartell58.cardinal.Main`; argument
parsing and the `--help`/`-o` handling live in `Cli`.

## Scope

`Cardinal` targets the common JVM bytecode produced by `javac` for structured code.
The following are rendered faithfully:

- classes, interfaces (`static`/`abstract`), fields with `ConstantValue` initializers
- constructors and method dispatch (`this(...)`, `super(...)`, `this` fields)
- `if`/`else` chains, `?:` and comparison helpers
- `while`, `do-while` and `while(true)` loops reconstructed from backedges
- `switch` (grouped key tables via `tableswitch`/`lookupswitch`), with `default`
- string concatenation via `StringConcatFactory` recipes
- `new`/`new[]`/cast/instanceof, static/instance fields and method calls; `dup_*`, `pop`, `swap` stack idioms
- lambda and method-reference call sites (inline `(args) -> body` / `Owner::method`,
  including captured variables and generics via the `Signature` attribute)
- anonymous and nested classes rendered inline, generic interfaces with type parameters

The `LocalVariableTable` (compile with `javac -g`) is honored for names and types;
parameters keep their `arg0..n` names. Unsupported constructs degrade to a comment
on the offending instruction rather than crashing the whole class.

## Round-trip test

`RoundTripTest.java` (under `src/test/java`) compiles the fixture
`src/test/resources/sample/Demo.java`, decompiles the result, recompiles the
emitted source, and asserts that every tested method returns identical values in
both classes.

`LambdaStressTest.java` does the same for a lambda-heavy fixture
(`src/test/resources/sample/stress/Stress.java`) and for
[`DecompileStress`](#decompilation-stress-test): ten assertions covering lambda
synthesis, method references, anonymous/nested classes, generic interfaces,
string concatenation and round-trip behaviour, plus a check that the emitted
source contains no decompiler placeholder markers.

## Decompilation Stress Test

[`DecompileStress`](examples/DecompileStress.java) is a deliberately complex
class used to stress the bytecode parser, the control-flow analysis and the Java
code generator of `Cardinal`. It exercises, in a single class:

- lambdas and captured variables
- method references (`String::toUpperCase`, `StringBuilder::append`, `Object::toString`)
- `invokedynamic` (`LambdaMetafactory`, `StringConcatFactory`)
- try/finally (`run()`)
- generics (`Processor<T>`, `Nested<T>`, `genericStuff`)
- anonymous classes (`anonymousExample`)
- nested classes and generic interfaces
- `switch` on a `while(true)` loop state machine (`obfuscatedFlow`)
- loops
- bitwise operations (`&`, `^`, rotates, reverses)
- exception metadata
- `StringBuilder` reduction (`collect`-style accumulation)
- static fields with initializers
- JDK API calls (`Objects.hashCode`, `Integer.rotateLeft`, `Integer.countBits`, streams)

### Original source

[`examples/DecompileStress.java`](examples/DecompileStress.java)

Compile it and run `Cardinal` against the produced class:

```bash
javac --release 21 -d out examples/DecompileStress.java
java -jar build/libs/cardinal-0.1.0.jar out/DecompileStress.class
```

### Cardinal output

The output below is the **real** output produced by `Cardinal` (no manual
editing). The method `obfuscatedFlow` — a `switch`-driven `while(true)` state
machine — is shown in full; the rest of the file is representative of the
remaining methods. The complete output is in
[`examples/DecompileStress.decompiled.java`](examples/DecompileStress.decompiled.java).

```java
public final class DecompileStress {
  private static final java.util.Map<java.lang.String, java.lang.Integer> CACHE;

  public DecompileStress() {
    super();
    return;
  }

  public static int obfuscatedFlow(int arg0) {
    int v0 = 0;
    int v1 = arg0&7;
    while (true) {
      switch (v1) {
      case 0:
          v0 += 17;
          v1 = (arg0^v0)&3;
          continue;
      case 1:
          v0 = v0^85;
          v1 = v0+arg0&7;
          continue;
      case 2:
          v0 = v0*3;
          if ((v0&1) != 0) {
            v1 = 6;
            continue;
          }
          else {
            v1 = 4;
            continue;
          }
      case 3:
          v0 = v0-arg0;
          v1 = Math.abs(v0)&7;
          continue;
      case 4:
          v0 = Integer.rotateLeft(v0, 5);
          v1 = 7;
          continue;
      case 5:
          v0 = v0+arg0*13;
          v1 = 1;
          continue;
      case 6:
          v0 = Integer.reverse(v0);
          v1 = 3;
          continue;
      default:
          return v0;
      }
    }
  }
}
```

The emitted source recompiles cleanly and reproduces the original behaviour for
`transform`, `obfuscatedFlow` and `genericStuff`.

**Known limitation:** `run()` contains a lambda whose body has a `try/finally`
block. Cardinal does not yet reconstruct exception regions inside lambda bodies,
so `run()` degrades to a typed `null` with a comment explaining the limitation.
The remaining methods are decompiled faithfully.

## Releases

Published binaries are attached to [GitHub Releases](https://github.com/kartell58/cardinal/releases):
each release tag (`v0.1.0`, …) carries the built `cardinal-<version>.jar`. The
repository itself stays focused on source; no generated JARs are committed.

To build the JAR from source:

```bash
./gradlew -x test jar      # produces build/libs/cardinal-0.1.0.jar
```

## Layout

| Path | Contents |
|---|---|
| `src/main/java/…/Main.java` | entry point — a one-line bootstrap around `Cli` |
| `src/main/java/…/Cli.java` | arg parsing, `--help`, `-o`/`--output`, input collection |
| `src/main/java/…/ClassFile.java` | raw class-file parser: constant pool, fields, methods, Code, LVT, ConstantValue |
| `src/…/Insn.java` | opcode constants and bytecode-to-`Insn` decoder |
| `src/…/Cfg.java` | basic blocks, reverse post-order, dominators & post-dominators, backedges |
| `src/…/Struc.java` | structured statement tree (`if`, `loop`, `switch`) built from the CFG |
| `src/…/Sim.java` | stack simulation, expression reconstruction, code emission |
| `src/…/Writer.java` | class/field/method signature rendering |
| `src/…/Types.java` | descriptor/name helpers, primitive widths |
| `examples/` | `DecompileStress` — a public stress-test class and its real decompiled output |

See `docs/ARCHITECTURE.md` for the phase-by-phase pipeline and the design notes.