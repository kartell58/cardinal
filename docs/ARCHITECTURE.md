# Architecture

Cardinal decompiles a class in five phases. Each phase is deliberately short and
operates on the output of the previous one.

## 1. Parsing — `ClassFile`

A hand-written reader over the raw bytes (`ClassFile`). The constant pool is a
1-based array of sealed `Cp` entries. The parser enforces the layout invariant
`len = u4(); saved = p + len; … p = saved;` so that out-of-order attributes
(`Code`, `Exceptions`, `ConstantValue`, `LocalVariableTable`) never corrupt the
cursor.

Method bytecode is stored as raw `byte[]`; only `Code`-level metadata (`maxLocals`,
the exception table, the LVT) is lifted here. `ConstantValue` on fields is resolved
to a Java literal so `static final int MAX = 10;` round-trips even though javac
emits no `<clinit>` store for compile-time constants.

## 2. Decoding — `Insn`

`Insn.decode(byte[])` walks the bytecode, grouping it into instruction records of
`(pc, op, var, con, cpool, tars[])`. Branch and `tableswitch`/`lookupswitch` targets
are resolved to absolute PCs here. Every opcode is given a constant (`Insn`), so the
remaining phases read them by name rather than magic numbers.

## 3. Control flow — `Cfg`

`Cfg.build` turns the instruction stream into a control-flow graph of basic blocks:

- **leaders** are the entry, every branch target, and every post-branch fall-through;
- **raw successors** come from branch targets and fall-through, with no phantom
  successor for blocks ending in `return`/`throw`;
- **reverse post-order** (iterative DFS, marking on push, `done` on pop) numbers the
  blocks and re-indexes `succ`/`pred`, avoiding duplicate visits;
- **dominators** via the classic iterative algorithm; **post-dominators** computed on
  the reversed graph with a virtual exit node (`m = n + 2`, so the exit index never
  collides with a block). The exit edge is modelled as a predecessor of each
  terminating block;
- **backedges** are `src -> head` where `src` reaches `head` and `head` dominates `src`.

The immediate-post-dominator tree (`ipd`, `pdDepth`) and the `join(a, b)` = LCA
query drive control-structure reconstruction.

## 4. Structuring — `Struc`

`Struc.build` walks the CFG in RPO and emits a statement tree:

| node | built from |
|---|---|
| `Blk` | straight-line basic block |
| `If(head, then, els, join)` | a condition block whose two successors converge at `join(a, b)` |
| `Loop(head, kind, trailer, join, body, exits)` | a backed loop head, classified `WHILE`/`DO`/`TRUE` |
| `Sw(head, cases, join)` | a `tableswitch`/`lookupswitch` acting on a decoded key column |

Key heuristics:

- an `If` needs a real post-dominator join; otherwise the arms are treated as
  divergent (`divergentIf`) — each arm is walked to its own terminator;
- a loop whose header is a two-way condition with exactly one loop-`in` successor,
  and a distinct backedge source, is a `WHILE`; the header's condition is the
  taken-branch test and the body is the pulled region;
- `trailer` = the `do-while` condition block whose back-edge is *itself* (self-loop):
  a header block that holds `body + trailing test` is classified `DO` and the body
  is that block minus its final test;
- otherwise `while (true)` with `break`/`continue` resolved from the `exits` set.

## 5. Simulation & emission — `Sim` + `Writer`

Starting from the structured tree, `Sim` walks the original block instructions in
order and simulates an abstract stack of `X` expression records
`(text, precedence, width, flavor)`:

- **consume**: binary ops `bin(a, op, prec, b)`, unary `neg`, `cmp0`, casts, index
  expressions, `invoke*` (with `invokespecial` folded into `new …(…)`),
  `StringConcatFactory` recipes;
- **branches**: a block's trailing condition is simulated via `condBlock`, which
  pools the operands and yields `branchOf` — the *taken* test; `while` loops negate
  it at the opcode level (`>=` → `<`) so rendered conditions stay clean;
- **control flow**: `goto` either continues a loop, `break`s toward an `exits`
  target, is swallowed at a structured `join` (`ctx.suppress`), or—when it feeds a
  value across a block boundary—materializes stack carries through typed temporaries
  `$tN` (`materializeCarried`); types are recovered by re-simulating the target
  block's pops (`carryTypes`), `$tN` declarations are hoisted to the top of the
  method so scope always holds;
- **returns**: leftover statement-like stack entries are flushed (`flushLeftover`);
  `return;` is suppressed inside `<clinit>` blocks, where javac disallows it.

`Writer` renders the class shell: modifiers (via a shared access-flag table), fields,
constructors, methods, and the `static {}` initializer.