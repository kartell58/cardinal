# Changelog

All notable changes to the Cardinal decompiler are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-09-06

First public release.

### Added

- Independent `.class` parser: constant pool, fields, methods, `Code`,
  `LocalVariableTable` and `ConstantValue`
- Structured control-flow reconstruction (`if`/`else`, `while`/`do-while`,
  `switch`) built from a dominator-based CFG
- Expression reconstruction via stack simulation: arithmetic, bitwise ops,
  casts, array/field access, method calls, ternary and comparison helpers
- String concatenation via `StringConcatFactory` recipe decoding
  (`makeConcatWithConstants`)
- Lambda and method-ref call sites via `LambdaMetafactory`: inline
  `(args) -> body` lambdas, `Owner::method` references and captured variables
- Anonymous and nested classes rendered inline, with generic interfaces
  (`Signature` attribute support: class, method and field signatures)
- Round-trip test suite (`RoundTripTest`) and a lambda/stress test suite
  (`LambdaStressTest`)
- CLI with `--help` and `-o`/`--output` directory mode
- Zero runtime dependencies (JUnit is test-only)

### Known limitations

- Lambdas whose bodies contain exception handlers (e.g. `try/finally`) are not
  reconstructed; the call site degrades to a typed `null` with an explanatory
  comment
- Local variable names and types depend on the `LocalVariableTable`; classes
  compiled with `javac -g:none` degrade to `vN`/`argN` names