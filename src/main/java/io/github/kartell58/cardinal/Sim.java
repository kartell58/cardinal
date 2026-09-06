package io.github.kartell58.cardinal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stack simulation, expression reconstruction and statement emission over a {@link Struc} tree.
 *
 * <p>The method body is rendered in a single structured walk. Operand-stack values are
 * materialized as strings with a precedence number so parentheses are inserted correctly when
 * subexpressions are composed. Values that survive an if/else merge (e.g. {@code return c ? a : b})
 * are pulled into indexed temps ({@code $t0..}) pre-declared with a type inferred from the join
 * block's consuming instructions.
 */
final class Sim {

    // Java operator precedence (higher binds tighter).
    static final int P_ASSIGN = 10, P_TERN = 20, P_OROR = 30, P_ANDAND = 32, P_BOR = 34,
            P_BXOR = 36, P_BAND = 38, P_EQ = 40, P_REL = 44, P_SHIFT = 50, P_ADD = 60,
            P_MUL = 70, P_CAST = 80, P_UNARY = 82, P_POST = 90, P_ATOM = 100;

    private static final int K_NEW = 1, K_ANON = 2;

    /** An expression: rendered text plus enough info to parenthesize it later. */
    static class X {
        String s;
        final int p, w, kind;
        String ty;
        List<String> blk; // block body (un-indented lines) when this is a multi-line construct
        String tail;      // closing token, e.g. "}" or "};", for multi-line constructs

        X(String s, int p, int w, int kind) { this(s, p, w, kind, null); }

        X(String s, int p, int w, int kind, String ty) {
            this.s = s;
            this.p = p;
            this.w = w;
            this.kind = kind;
            this.ty = ty;
        }

        static X v(String s, int w) { return new X(s, P_ATOM, w, 0); }

        static X typ(X x, String t) { if (x != null && t != null) x.ty = t; return x; }

        /** A multi-line expression: {@code head + "{" + blk + tail}. */
        static X block(String head, List<String> blk, String tail, int kind, String ty) {
            X x = new X(head, P_ATOM, 1, kind, ty);
            x.blk = blk;
            x.tail = tail;
            return x;
        }

        /** Single-line rendering used when the construct must appear inside an expression. */
        String collapsed() {
            if (blk == null) return s;
            return s + " { " + String.join(" ", blk) + " " + tail.trim();
        }

        static X neg(X x) {
            if (x != null && x.s != null) {
                if (x.s.startsWith("(") && x.s.endsWith(")")) {
                    String inner = x.s.substring(1, x.s.length() - 1);
                    if (inner.contains(" == ")) return new X("!" + inner.replace(" == ", " != "), P_UNARY, x.w, 0, x.ty);
                    if (inner.contains(" != ")) return new X("!" + inner.replace(" != ", " == "), P_UNARY, x.w, 0, x.ty);
                }
                if (x.s.startsWith("!(") && x.s.endsWith(")") && x.s.length() > 3) {
                    return new X(x.s.substring(2, x.s.length() - 1), P_UNARY, x.w, 0, x.ty);
                }
            }
            return new X("!" + wrap(x, P_UNARY, false), P_UNARY, x.w, 0, x.ty);
        }

        /** Left operand wraps when looser than p, right operand when looser or equal (left assoc). */
        static X bin(X a, String op, int p, X b) {
            String t = wrap(a, p, false) + op + wrap(b, p, true);
            return new X(t, p, Math.max(a.w, b.w), 0);
        }

        static String wrap(X x, int p, boolean right) {
            if (x == null) return "?";
            if (right ? x.p <= p : x.p < p) return "(" + x.collapsed() + ")";
            return x.collapsed();
        }

        static X neq(X a, String op) { return new X(wrap(a, P_UNARY, false) + op, P_UNARY, a.w, 0); }

        static X cast(String type, X x) {
            return new X("(" + type + ") " + wrap(x, P_CAST, false), P_CAST, x.w, 0);
        }

        static X post(X base, String tail) {
            return new X(wrap(base, P_POST, false) + tail, P_POST, base.w, 0);
        }

        /** Duplicate for dup-family instructions; shared for new/anonymous tokens so finals propagate. */
        static X dup(X x) {
            if (x.kind == K_NEW || x.kind == K_ANON) return x;
            X d = new X(x.s, x.p, x.w, x.kind, x.ty);
            d.blk = x.blk;
            d.tail = x.tail;
            return d;
        }
    }

    /** Accumulated output lines at a fixed indent level. */
    static final class Code {
        final List<String> lines = new ArrayList<>();
        final int ind;

        Code(int ind) { this.ind = ind; }

        void line(String s) { lines.add(pad(ind) + s); }

        void join(Code sub) { lines.addAll(sub.lines); }

        void dropTrailingContinue() {
            String p = pad(ind);
            while (!lines.isEmpty() && lines.get(lines.size() - 1).equals(p + "continue;"))
                lines.remove(lines.size() - 1);
        }

        static String pad(int n) { return "  ".repeat(n); }
    }

    /** A structured construct frame for break/continue resolution. */
    record Lf(int head, int trailer, int[] exits) {}

    static final class Ctx {
        final Cfg g;
        final ClassFile cf;
        final boolean isStatic;
        final String[] names, types;
        final boolean[] decl;
        final String mret;
        final List<X> st = new ArrayList<>();
        final List<Integer> suppress = new ArrayList<>();
        final Deque<Lf> loops = new ArrayDeque<>();
        final java.util.Set<Integer> dtEmited = new java.util.HashSet<>();
        final java.util.LinkedHashMap<Integer, String> temps = new java.util.LinkedHashMap<>();
        final java.util.Map<String, ClassFile> siblings;
        int regionBase;
        final int[] slotToCompact; // maps slot -> compact index for unnamed locals

        Ctx(Cfg g, ClassFile cf, boolean isStatic, String[] names, String[] types, boolean[] decl, String mret) {
            this(g, cf, isStatic, names, types, decl, mret, null);
        }

        Ctx(Cfg g, ClassFile cf, boolean isStatic, String[] names, String[] types, boolean[] decl, String mret,
                java.util.Map<String, ClassFile> siblings) {
            this(g, cf, isStatic, names, types, decl, mret, siblings, "");
        }

        Ctx(Cfg g, ClassFile cf, boolean isStatic, String[] names, String[] types, boolean[] decl, String mret,
                java.util.Map<String, ClassFile> siblings, String localPrefix) {
            this.g = g;
            this.cf = cf;
            this.isStatic = isStatic;
            this.names = names;
            this.types = types;
            this.decl = decl;
            this.mret = mret;
            this.siblings = siblings;
            this.vp = localPrefix;

            int nslots = names.length;
            this.slotToCompact = new int[nslots];
            java.util.Arrays.fill(this.slotToCompact, -1);
            int compact = 0;
            for (int i = 0; i < nslots; i++) {
                if (names[i] == null && !decl[i]) {
                    this.slotToCompact[i] = compact++;
                }
            }
        }

        void push(X x) { st.add(x); }

        X pop() { return st.isEmpty() ? X.v("/*<stack underflow>*/", 1) : st.remove(st.size() - 1); }

        X peek() { return st.isEmpty() ? X.v("/*<stack underflow>*/", 1) : st.get(st.size() - 1); }

        void reset(List<X> base) { st.clear(); st.addAll(base); }

        int size() { return st.size(); }

        final String vp; // prefix for auto-named temporaries, isolates lambda bodies from the enclosing method

        String name(int slot) {
            if (!isStatic && slot == 0) return "this";
            if (names[slot] != null) return names[slot];
            int c = slotToCompact != null && slot < slotToCompact.length ? slotToCompact[slot] : -1;
            if (c >= 0) return vp + "v" + c;
            return vp + "v" + slot; // fallback
        }

        void recordTemp(int idx, String type) {
            if (dtEmited.add(idx)) temps.putIfAbsent(idx, type);
        }
    }

    private final Ctx ctx;

    private Sim(Ctx ctx) { this.ctx = ctx; }

    /** Renders the body of method {@code m} (lines at {@code indent}). */
    static String body(Cfg g, ClassFile cf, ClassFile.Method m, int indent) {
        return body(g, cf, m, indent, null, null);
    }

    /**
     * Renders the body with {@code override} param names (indexed by parameter, not slot; slot 0 stays "this"
     * for instance methods) and {@code siblings} context for nested/anonymous class resolution.
     */
    static String body(Cfg g, ClassFile cf, ClassFile.Method m, int indent, String[] override,
            java.util.Map<String, ClassFile> siblings) {
        int maxLocals = m.code().maxLocals();
        boolean isStatic = (m.access() & ClassFile.ACC_STATIC) != 0;
        String[] parts = Types.method(m.desc());
        String[] pt = slice(parts, 0, parts.length - 1);
        String[] psig = Types.sigMethod(m.sig()); // generic parameter types, when present
        int nslots = Math.max(maxLocals, pt.length + 1);
        String[] names = new String[nslots];
        String[] types = new String[nslots];
        boolean[] decl = new boolean[nslots];
        boolean[] isParam = new boolean[nslots];

        int slot = isStatic ? 0 : 1; // slot 0 = this for instance methods
        for (int i = 0; i < pt.length; i++) {
            names[slot] = override != null && i < override.length ? override[i] : "arg" + i;
            types[slot] = psig != null ? psig[i] : pt[i];
            decl[slot] = true;
            isParam[slot] = true;
            slot += Types.width(pt[i]);
        }
        for (ClassFile.Lvt l : m.code().lvt()) {
            if (l.index() < nslots && !isParam[l.index()] && l.len() > 0) {
                names[l.index()] = l.name();
                types[l.index()] = Types.field(l.desc());
            }
        }

        String[] mp = Types.method(m.desc());
        String mret = psig != null ? psig[psig.length - 1] : mp[mp.length - 1];
        Sim sim = new Sim(new Ctx(g, cf, isStatic, names, types, decl,
                mret, siblings, override != null ? "$" : ""));
        Code code = new Code(indent);
        sim.doSeq(Struc.build(g), code);

        List<String> out = new ArrayList<>(code.lines);
        List<String> pre = new ArrayList<>();
        for (var e : sim.ctx.temps.entrySet()) {
            pre.add("  ".repeat(indent) + e.getValue() + " $t" + e.getKey()
                    + " = " + zeroInit(e.getValue()) + ";");
        }
        pre.addAll(out);
        return String.join("\n", pre);
    }

    private static String zeroInit(String t) {
        return switch (t) {
            case "boolean" -> "false";
            case "byte", "short", "int" -> "0";
            case "long" -> "0L";
            case "char" -> "'\\0'";
            case "float" -> "0F";
            case "double" -> "0.0";
            default -> "null"; // reference types, arrays, type variables and generics
        };
    }

    private static String[] slice(String[] a, int f, int t) {
        String[] r = new String[t - f];
        System.arraycopy(a, f, r, 0, t - f);
        return r;
    }

    // ===== structured tree walking =====

    private void doSeq(Struc.S node, Code code) {
        switch (node) {
            case Struc.Seq s -> { for (Struc.S c : s.s()) doSeq(c, code); }
            case Struc.Blk b -> blk(b.bb(), code);
            case Struc.If f -> ifter(f, code);
            case Struc.Loop lp -> looper(lp, code);
            case Struc.Sw sw -> switcher(sw, code);
            default -> {}
        }
    }

    // ===== basic block simulation =====

    private void blk(int bb, Code code) {
        int start = ctx.g.bbs.get(bb).start(), end = ctx.g.bbs.get(bb).end();
        List<X> save = List.copyOf(ctx.st);
        for (int i = start; i <= end; i++) {
            Insn in = ctx.g.all.get(i);
            if (in.op == Insn.GOTO || in.op == Insn.GOTO_W) {
                handleGoto(in, save, code);
                return;
            }
            if (isCond(op(in)) && i == end) return;
            if (sim(in, code)) return;
        }
    }

    private static int op(Insn in) { return in.op; }

    private void handleGoto(Insn in, List<X> save, Code code) {
        int tars = ctx.g.blockOfPc[in.tars[0]];
        int n = ctx.st.size() - save.size();
        boolean toHead = false;
        for (Lf f : ctx.loops) if (f.head == tars) toHead = true;
        if (n > 0 && !toHead) {
            String[] ts = carryTypes(tars, n);
            for (int k = 0; k < n; k++) {
                ctx.recordTemp(ctx.regionBase + k, ts[n - 1 - k]);
            }
            materializeCarried(n, code);
        } else {
            if (n > 0) code.line("// stack carry across loop back-edge");
            ctx.st.clear();
            ctx.st.addAll(save);
        }
        flow(tars, code);
    }

    private void flow(int target, Code code) {
        for (Lf f : ctx.loops) {
            if (f.trailer == target || f.head == target) { code.line("continue;"); return; }
            for (int e : f.exits) if (e == target) { code.line("break;"); return; }
        }
        for (int s : ctx.suppress) if (s == target) return;
        int first = ctx.g.bbs.get(target).start();
        code.line("// goto pc=" + ctx.g.all.get(first).pc + " (unstructured)");
    }

    /** Pulls the top {@code n} stack values into indexed temps (declared up-front by the caller). */
    private void materializeCarried(int n, Code code) {
        if (n <= 0) return;
        X[] ts = new X[n];
        for (int p = 0; p < n; p++) {
            X x = ctx.pop();
            int idx = ctx.regionBase + (n - 1 - p);
            ts[n - 1 - p] = X.v("$t" + idx, x.w);
            if (!x.s.startsWith("$t")) {
                String val = x.s;
                if ("boolean".equals(ctx.temps.get(idx)) && (val.equals("1") || val.equals("0")))
                    val = val.equals("1") ? "true" : "false";
                code.line("$t" + idx + " = " + val + ";");
            }
        }
        for (int k = n - 1; k >= 0; k--) ctx.push(ts[k]);
    }

    // ===== sim: individual instructions =====

    private boolean sim(Insn in, Code code) {
        switch (in.op) {
            case Insn.NOP -> {}
            case Insn.ACONST_NULL -> ctx.push(X.v("null", 1));
            case Insn.ICONST_M1 -> ctx.push(X.v("-1", 1));
            case Insn.ICONST_0, Insn.ICONST_1, Insn.ICONST_2, Insn.ICONST_3, Insn.ICONST_4, Insn.ICONST_5 ->
                    ctx.push(X.v(String.valueOf(in.con), 1));
            case Insn.LCONST_0 -> ctx.push(X.v("0L", 2));
            case Insn.LCONST_1 -> ctx.push(X.v("1L", 2));
            case Insn.FCONST_0 -> ctx.push(X.v("0.0f", 1));
            case Insn.FCONST_1 -> ctx.push(X.v("1.0f", 1));
            case Insn.FCONST_2 -> ctx.push(X.v("2.0f", 1));
            case Insn.DCONST_0 -> ctx.push(X.v("0.0d", 2));
            case Insn.DCONST_1 -> ctx.push(X.v("1.0d", 2));
            case Insn.BIPUSH, Insn.SIPUSH -> ctx.push(X.v(String.valueOf(in.con), 1));
            case Insn.LDC, Insn.LDC_W, Insn.LDC2_W -> ctx.push(ldc(in.cpool, in.op));
            case Insn.ILOAD, Insn.LLOAD, Insn.FLOAD, Insn.DLOAD, Insn.ALOAD ->
                    ctx.push(X.typ(X.v(ctx.name(in.var), loadW(in.op)), ctx.types[in.var]));
            case Insn.ILOAD_0, Insn.ILOAD_1, Insn.ILOAD_2, Insn.ILOAD_3, Insn.LLOAD_0, Insn.LLOAD_1,
                    Insn.LLOAD_2, Insn.LLOAD_3, Insn.FLOAD_0, Insn.FLOAD_1, Insn.FLOAD_2, Insn.FLOAD_3,
                    Insn.DLOAD_0, Insn.DLOAD_1, Insn.DLOAD_2, Insn.DLOAD_3, Insn.ALOAD_0, Insn.ALOAD_1,
                    Insn.ALOAD_2, Insn.ALOAD_3 ->
                    ctx.push(X.typ(X.v(ctx.name(in.var), shortLoadW(in.op)), ctx.types[in.var]));
            case Insn.ISTORE, Insn.LSTORE, Insn.FSTORE, Insn.DSTORE, Insn.ASTORE, Insn.ISTORE_0, Insn.ISTORE_1,
                    Insn.ISTORE_2, Insn.ISTORE_3, Insn.LSTORE_0, Insn.LSTORE_1, Insn.LSTORE_2, Insn.LSTORE_3,
                    Insn.FSTORE_0, Insn.FSTORE_1, Insn.FSTORE_2, Insn.FSTORE_3, Insn.DSTORE_0, Insn.DSTORE_1,
                    Insn.DSTORE_2, Insn.DSTORE_3, Insn.ASTORE_0, Insn.ASTORE_1, Insn.ASTORE_2, Insn.ASTORE_3 ->
                    store(in, code);
            case Insn.IINC -> iinc(in, code);
            case Insn.POP -> popDead(code);
            case Insn.POP2 -> popDead2(code);
            case Insn.DUP -> ctx.push(X.dup(ctx.peek()));
            case Insn.SWAP -> { X a = ctx.pop(); X b = ctx.pop(); ctx.push(a); ctx.push(b); }
            case Insn.DUP_X1 -> dupX1();
            case Insn.DUP_X2 -> dupX2();
            case Insn.DUP2 -> dup2();
            case Insn.DUP2_X1 -> dup2x1();
            case Insn.DUP2_X2 -> dup2x2();
            case Insn.IADD, Insn.LADD, Insn.FADD, Insn.DADD -> arith("+", P_ADD);
            case Insn.ISUB, Insn.LSUB, Insn.FSUB, Insn.DSUB -> arith("-", P_ADD);
            case Insn.IMUL, Insn.LMUL, Insn.FMUL, Insn.DMUL -> arith("*", P_MUL);
            case Insn.IDIV, Insn.LDIV, Insn.FDIV, Insn.DDIV -> arith("/", P_MUL);
            case Insn.IREM, Insn.LREM, Insn.FREM, Insn.DREM -> arith("%", P_MUL);
            case Insn.ISHL, Insn.LSHL, Insn.ISHR, Insn.LSHR, Insn.IUSHR, Insn.LUSHR -> shift(in.op);
            case Insn.IAND, Insn.LAND -> arith("&", P_BAND);
            case Insn.IOR, Insn.LOR -> arith("|", P_BOR);
            case Insn.IXOR, Insn.LXOR -> arith("^", P_BXOR);
            case Insn.INEG, Insn.LNEG, Insn.FNEG, Insn.DNEG -> ctx.push(X.neq(ctx.pop(), "-"));
            case Insn.I2L, Insn.I2F, Insn.I2D, Insn.L2F, Insn.L2D, Insn.F2D ->
                    ctx.push(ctx.pop()); // widening primitive conversions don't need explicit casts
            case Insn.L2I, Insn.F2I, Insn.F2L, Insn.D2I, Insn.D2L, Insn.D2F,
                    Insn.I2B, Insn.I2C, Insn.I2S ->
                    ctx.push(X.cast(convType(in.op), ctx.pop()));
            case Insn.LCMP, Insn.FCMPL, Insn.FCMPG, Insn.DCMPL, Insn.DCMPG -> cmp();
            case Insn.IALOAD, Insn.LALOAD, Insn.FALOAD, Insn.DALOAD, Insn.AALOAD, Insn.BALOAD,
                    Insn.CALOAD, Insn.SALOAD -> xaload();
            case Insn.IASTORE, Insn.LASTORE, Insn.FASTORE, Insn.DASTORE, Insn.AASTORE, Insn.BASTORE,
                    Insn.CASTORE, Insn.SASTORE -> xastore(code);
            case Insn.GETSTATIC -> ctx.push(field(in.cpool, null, true));
            case Insn.GETFIELD -> ctx.push(field(in.cpool, ctx.pop(), false));
            case Insn.PUTSTATIC -> putfield(in.cpool, code, null, ctx.pop());
            case Insn.PUTFIELD -> { X v = ctx.pop(); putfield(in.cpool, code, ctx.pop(), v); }
            case Insn.INVOKEVIRTUAL, Insn.INVOKESPECIAL, Insn.INVOKEINTERFACE, Insn.INVOKESTATIC ->
                    invoke(in, code);
            case Insn.INVOKEDYNAMIC -> invoked(in, code);
            case Insn.NEW -> newX(in.cpool, code);
            case Insn.NEWARRAY -> newarray(in.con);
            case Insn.ANEWARRAY -> anewarray(in.cpool, code);
            case Insn.MULTIANEWARRAY -> multianewarray(in);
            case Insn.ARRAYLENGTH -> ctx.push(X.post(ctx.pop(), ".length"));
            case Insn.CHECKCAST -> {
                    String castType = clsName(in.cpool);
                    X v = ctx.pop();
                    if (v.ty != null && v.ty.equals(castType)) {
                        ctx.push(v);
                    } else {
                        ctx.push(X.cast(castType, v));
                    }
                }
            case Insn.INSTANCEOF -> instanceofX(in.cpool);
            case Insn.MONITORENTER, Insn.MONITOREXIT -> code.line(in.op == Insn.MONITORENTER
                    ? "// monitorenter " + ctx.pop().s : "// monitorexit " + ctx.pop().s);
            case Insn.ATHROW -> { code.line("throw " + ctx.pop().s + ";"); return true; }
            case Insn.IRETURN, Insn.LRETURN, Insn.FRETURN, Insn.DRETURN, Insn.ARETURN ->
                { X v = ctx.pop(); flushLeftover(code); assignLine(code, "return ", v); return true; }
        case Insn.RETURN -> { flushLeftover(code); code.line("return;"); return true; }
            default -> code.line("// " + insnName(in.op) + " (unhandled)");
        }
        return false;
    }

    private static boolean isCond(int opx) {
        return (opx >= Insn.IFEQ && opx <= Insn.IF_ACMPNE) || opx == Insn.IFNULL || opx == Insn.IFNONNULL;
    }

    private static String insnName(int opx) {
        for (java.lang.reflect.Field f : Insn.class.getFields()) {
            try {
                if (f.getType() == int.class && f.getInt(null) == opx) return f.getName();
            } catch (IllegalAccessException ignored) {}
        }
        return "op" + opx;
    }

    private static int loadW(int opx) {
        return (opx == Insn.LLOAD || opx == Insn.DLOAD) ? 2 : 1;
    }

    private static int shortLoadW(int opx) {
        if (opx >= Insn.LLOAD_0 && opx <= Insn.LLOAD_3) return 2;
        if (opx >= Insn.DLOAD_0 && opx <= Insn.DLOAD_3) return 2;
        return 1;
    }

    // ===== locals =====

    private void store(Insn in, Code code) {
        String name = ctx.name(in.var);
        X v = ctx.pop();
        String ty = ctx.types[in.var] != null ? ctx.types[in.var]
                : (v.ty != null ? v.ty : defaultStoreType(in.op));
        if (ctx.decl[in.var]) assignLine(code, name + " = ", v);
        else {
            assignLine(code, ty + " " + name + " = ", v);
            ctx.decl[in.var] = true;
        }
    }

    /** Emits {@code target + value;}, expanding multi-line {@code blk} constructs. */
    private void assignLine(Code code, String target, X v) {
        if (v.blk == null) {
            code.line(target + v.s + ";");
            return;
        }
        code.line(target + v.s + " {");
        Code sub = new Code(code.ind + 1);
        for (String l : v.blk) sub.line(l);
        code.join(sub);
        code.line(v.tail + ";");
    }

    private void iinc(Insn in, Code code) {
        String name = ctx.name(in.var);
        if (!ctx.decl[in.var]) {
            code.line("int " + name + " = 0;");
            ctx.decl[in.var] = true;
        }
        code.line(in.con >= 0 ? name + " += " + in.con + ";" : name + " -= " + (-in.con) + ";");
    }

    private static String defaultStoreType(int opx) {
        if (opx >= Insn.ISTORE_0 && opx <= Insn.ASTORE_3
                || opx >= Insn.ISTORE && opx <= Insn.ASTORE) {
            if (opx >= Insn.LSTORE_0 && opx <= Insn.LSTORE_3 || opx == Insn.LSTORE) return "long";
            if (opx >= Insn.FSTORE_0 && opx <= Insn.FSTORE_3 || opx == Insn.FSTORE) return "float";
            if (opx >= Insn.DSTORE_0 && opx <= Insn.DSTORE_3 || opx == Insn.DSTORE) return "double";
            if (opx >= Insn.ISTORE_0 && opx <= Insn.ISTORE_3 || opx == Insn.ISTORE) return "int";
            return "Object"; // reference (ASTORE*)
        }
        return "Object";
    }

    // ===== constants =====

    private X ldc(int idx, int opx) {
        ClassFile.Cp e = ctx.cf.cp[idx];
        if (e instanceof ClassFile.Ci v) return X.v(String.valueOf(v.v()), 1);
        if (e instanceof ClassFile.Cf v) return X.v(fmt(v.v()) + "f", 1);
        if (e instanceof ClassFile.Cl v) return X.v(v.v() + "L", 2);
        if (e instanceof ClassFile.Cd v) return X.v(fmt(v.v()) + "d", 2);
        if (e instanceof ClassFile.Cs v) return X.v("\"" + esc(ctx.cf.utf(v.str())) + "\"", 1);
        if (e instanceof ClassFile.Cc c) return X.v(Types.name(ctx.cf.cls(idx)) + ".class", 1);
        return X.typ(X.v("null", 1), "java.lang.Object");
    }

    private static String fmt(float v) {
        return (Float.isNaN(v) || Float.isInfinite(v)) ? "(0.0f/0.0f)" : Float.toString(v);
    }

    private static String fmt(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? "(0.0d/0.0d)" : Double.toString(v);
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                case '\r' -> b.append("\\r");
                case 0 -> b.append("\\0");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    // ===== expressions =====

    private void arith(String op, int p) {
        X b = ctx.pop(), a = ctx.pop();
        ctx.push(X.bin(a, op, p, b));
    }

    private void shift(int opx) {
        X b = ctx.pop(), a = ctx.pop();
        String o = switch (opx) {
            case Insn.IUSHR, Insn.LUSHR -> ">>>";
            case Insn.ISHR, Insn.LSHR -> ">>";
            default -> "<<";
        };
        ctx.push(X.bin(a, o, P_SHIFT, b));
    }

    private void cmp() {
        X b = ctx.pop(), a = ctx.pop();
        String ta = X.wrap(a, P_REL, false), tb = X.wrap(b, P_REL, false);
        ctx.push(X.v("(" + ta + " < " + tb + " ? -1 : (" + ta + " > " + tb + " ? 1 : 0))", 1));
    }

    private void xaload() {
        X i = ctx.pop(), a = ctx.pop();
        ctx.push(X.post(a, "[" + i.s + "]"));
    }

    private void xastore(Code code) {
        X v = ctx.pop(), i = ctx.pop(), a = ctx.pop();
        code.line(X.wrap(a, P_POST, false) + "[" + i.s + "] = " + v.s + ";");
    }

    private void instanceofX(int idx) {
        X v = ctx.pop();
        ctx.push(new X(X.wrap(v, P_REL, false) + " instanceof " + clsName(idx), P_REL, 1, 0));
    }

    private X field(int idx, X recv, boolean isStatic) {
        String f = ctx.cf.refName(idx);
        String owner = Types.name(ctx.cf.refOwner(idx));
        int w = Types.width(Types.field(ctx.cf.refType(idx)));
        String base;
        if (isStatic) base = owner.equals(Types.name(ctx.cf.name)) ? "" : owner + ".";
        else base = recv.s.equals("this") ? "" : X.post(recv, ".").s;
        return X.typ(X.v(base + f, w), Types.field(ctx.cf.refType(idx)));
    }

    private void putfield(int idx, Code code, X recv, X v) {
        assignLine(code, field(idx, recv, recv == null).s + " = ", v);
    }

    // ===== invocation =====

    private void invoke(Insn in, Code code) {
        String[] pr = Types.method(ctx.cf.refType(in.cpool));
        int nargs = pr.length - 1;
        List<X> args = new ArrayList<>();
        for (int i = 0; i < nargs; i++) args.add(ctx.pop());
        Collections.reverse(args);
        String name = ctx.cf.refName(in.cpool);

        X recv = null;
        if (in.op != Insn.INVOKESTATIC) recv = ctx.pop();

        if (in.op == Insn.INVOKESPECIAL && name.equals("<init>")) {
            if (recv.kind == K_NEW) {
                String inst = Types.name(ctx.cf.refOwner(in.cpool));
                String el = collectionElement(inst, pr, args);
                if (el != null) {
                    recv.s += "<>";
                    recv.ty = inst + "<" + el + ">";
                }
                recv.s = recv.s + "(" + argsText(args, pr) + ")";
            } else if (recv.kind == K_ANON) {
                recv.s = recv.s + "(" + argsText(args, pr) + ")";
                return;
            } else if (recv.s.equals("this")) {
                String target = Types.name(ctx.cf.refOwner(in.cpool)).equals(Types.name(ctx.cf.name))
                        ? "this" : "super";
                code.line(target + "(" + argsText(args, pr) + ");");
            } else {
                code.line(recv.s + "(" + argsText(args, pr) + ");");
            }
            return;
        }

        String owner = Types.name(ctx.cf.refOwner(in.cpool));
        String base;
        if (recv == null) base = owner.equals(Types.name(ctx.cf.name)) ? "" : owner + ".";
        else if (recv.s.equals("this")) base = "";
        else if (owner.indexOf('$') >= 0) {
            if (sibling(owner) != null) { // nested type declared in this file: call through the typed receiver
                base = X.post(recv, ".").s;
            } else {
                String cur = Types.name(ctx.cf.name);
                if (owner.startsWith(cur + "$")) {
                    code.line("// (" + Types.typeText(owner, null) + " "
                            + X.post(recv, ".").s + name + "(...)): nested type not decompiled");
                    ctx.push(X.v("null", Types.width(pr[pr.length - 1])));
                    return;
                }
                String cast = owner.replace('$', '.');
                String r = X.post(recv, ".").s;
                base = "((" + cast + ") " + r.substring(0, r.length() - 1) + ").";
            }
        } else base = X.post(recv, ".").s;
        String rt = pr[pr.length - 1];
        ctx.push(X.typ(X.v(base + name + "(" + argsText(args, pr) + ")", Types.width(rt)), rt));
    }

    private static String argsText(List<X> args, String[] pr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            if (pr != null && i < pr.length - 1 && "char".equals(pr[i]))
                sb.append(charArg(args.get(i).s));
            else sb.append(args.get(i).collapsed());
        }
        return sb.toString();
    }

    /** Constructor argument element type for a diamond-initialized collection, or null to keep it raw. */
    private static String collectionElement(String inst, String[] pr, List<X> args) {
        if (!inst.startsWith("java.util.") || pr.length < 2 || args.isEmpty()) return null;
        String p = pr[0];
        if (!(p.startsWith("java.util.") && (p.contains("Collection") || p.contains("List")
                || p.contains("Set") || p.contains("Map") || p.contains("Queue")))) return null;
        String at = args.get(0).ty; // first ctor argument carries the element type, e.g. List<T>
        if (at == null) return null;
        int lo = at.indexOf('<');
        if (lo < 0) return null;
        int hi = at.indexOf('>', lo);
        if (hi < lo) return null;
        String body = at.substring(lo + 1, hi);
        int d = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '<') d++;
            else if (c == '>') d--;
            else if (c == ',' && d == 0) { body = body.substring(0, i); break; }
        }
        return body.trim();
    }

    /** Renders a char-typed argument: small int constants become character literals. */
    private static String charArg(String s) {
        int v;
        try {
            v = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return s;
        }
        if (v >= 32 && v <= 126 && v != '\'' && v != '\\') return "'" + (char) v + "'";
        return switch (v) {
            case 9 -> "'\\t'";
            case 10 -> "'\\n'";
            case 13 -> "'\\r'";
            case 0 -> "'\\0'";
            default -> "'\\u" + String.format("%04x", v & 0xffff) + "'";
        };
    }

    private static String argText(List<X> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(args.get(i).collapsed());
        }
        return sb.toString();
    }

    private void invoked(Insn in, Code code) {
        ClassFile.Cv cv = (ClassFile.Cv) ctx.cf.cp[in.cpool];
        String[] pr = Types.method(ctx.cf.natType(cv.nat()));
        String ret = pr[pr.length - 1];
        if (ctx.cf.bootstrap == null || cv.bs() >= ctx.cf.bootstrap.length) {
            code.line("// invokedynamic pc=" + in.pc + " cp=" + in.cpool + ": CallSite has no bootstrap");
            for (int i = 0; i < pr.length - 1; i++) ctx.pop(); // consume dynamic args
            ctx.push(X.typ(X.v("null", Types.width(ret)), ret));
            return;
        }
        int[] bsa = ctx.cf.bootstrap[cv.bs()];
        ClassFile.Cp be = ctx.cf.cp[bsa[0]];
        if (!(be instanceof ClassFile.Cm bmh)) {
            code.line("// invokedynamic pc=" + in.pc + " cp=" + in.cpool + ": unexpected bootstrap handle");
            for (int i = 0; i < pr.length - 1; i++) ctx.pop();
            ctx.push(X.typ(X.v("null", Types.width(ret)), ret));
            return;
        }
        String bmn = ctx.cf.refName(bmh.ref());

        if (bmn.startsWith("makeConcat")) {
            String recipe = null;
            for (int a : bsa) {
                ClassFile.Cp e = ctx.cf.cp[a];
                if (e instanceof ClassFile.Cs s) e = ctx.cf.cp[s.str()]; // recipe is a String -> Utf8
                if (e instanceof ClassFile.Cu u && u.v().contains("\u0001")) recipe = u.v();
            }
            if (recipe != null) {
                int n = recipe.length() - recipe.replace("\u0001", "").length();
                List<X> args = new ArrayList<>();
                for (int i = 0; i < n; i++) args.add(ctx.pop());
                Collections.reverse(args);
                List<String> pieces = new ArrayList<>();
                int ai = 0;
                StringBuilder lit = new StringBuilder();
                for (int i = 0; i < recipe.length(); i++) {
                    char c = recipe.charAt(i);
                    if (c == '\u0001') {
                        if (!lit.isEmpty()) {
                            pieces.add("\"" + esc(lit.toString()) + "\"");
                            lit.setLength(0);
                        }
                        pieces.add(args.get(ai++).collapsed());
                    } else {
                        lit.append(c);
                    }
                }
                if (!lit.isEmpty()) {
                    pieces.add("\"" + esc(lit.toString()) + "\"");
                }
                String txt = String.join("+", pieces);
                if (recipe.charAt(0) == '\u0001' && pieces.size() > 1) {
                    txt = "\"\"+" + txt;
                }
                ctx.push(X.v(txt, Types.width(ret)));
                return;
            }
        }

        if (bsa.length >= 3 && bsa.length <= 9
                && ctx.cf.cp[bsa[1]] instanceof ClassFile.Ct
                && ctx.cf.cp[bsa[2]] instanceof ClassFile.Cm
                && (bmn.equals("metafactory") || bmn.equals("altMetafactory"))) {
            String samDesc = ctx.cf.utf(((ClassFile.Ct) ctx.cf.cp[bsa[1]]).desc());
            ClassFile.Cm imh = (ClassFile.Cm) ctx.cf.cp[bsa[2]];
            List<X> caps = new ArrayList<>();
            for (int i = 0; i < pr.length - 1; i++) caps.add(ctx.pop()); // captured values = dynamic args
            Collections.reverse(caps);
            ctx.push(lambdaX(caps, samDesc, ctx.cf.refOwner(imh.ref()), ctx.cf.refName(imh.ref()),
                    ctx.cf.refType(imh.ref()), ret, code, in));
            return;
        }

        code.line("// invokedynamic pc=" + in.pc + " cp=" + in.cpool + ": bootstrap "
                + Types.name(ctx.cf.refOwner(bmh.ref())) + "." + bmn + " (unhandled)");
        for (int i = 0; i < pr.length - 1; i++) ctx.pop(); // consume dynamic args
        ctx.push(X.typ(X.v("null", Types.width(ret)), ret));
    }

    private static final Map<String, String> FUNC_TYPES = new HashMap<>();

    /** {@code java.util.function.Function<java.lang.String, java.lang.String>}: real SAM types filled into
     * the interface's own type-parameter count. For method references {@code real} starts with the
     * declaring class (the SAM's receiver slot). */
    private String functionalType(String internal, List<String> real) {
        String dot = Types.name(internal);
        String key = internal + "@" + String.join(",", real);
        String hit = FUNC_TYPES.get(key);
        if (hit != null) return hit;
        String out = dot;
        try {
            int n = 0;
            try (java.io.InputStream in = ClassLoader.getSystemResourceAsStream(
                    internal.replace('.', '/') + ".class")) {
                if (in != null) n = Types.typeParamCount(ClassFile.read(in.readAllBytes()).sig);
            }
            if (n > 0) {
                List<String> xs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String arg = i < real.size() ? real.get(i) : "Object";
                    xs.add(Types.name(arg));
                }
                out = dot + "<" + String.join(", ", xs) + ">";
            }
        } catch (Exception ignored) {
            out = dot;
        }
        FUNC_TYPES.put(key, out);
        return out;
    }

    /** Builds the value of a LambdaMetafactory call site: a method reference or a lambda expression. */
    private X lambdaX(List<X> caps, String samDesc, String iOwner, String iName, String iType,
            String ret, Code code, Insn in) {
        String[] sam = Types.method(samDesc);
        int declared = sam.length - 1;
        boolean ownSynth = iOwner.equals(ctx.cf.name) && iName.startsWith("lambda$");
        List<String> real = new ArrayList<>();
        if (ownSynth) {
            Collections.addAll(real, Types.method(iType)); // declared+captured params, return
        } else {
            real.add(Types.name(iOwner)); // SAM receiver slot
            Collections.addAll(real, Types.method(iType));
        }
        if (!ownSynth) {
            return X.typ(X.v(Types.typeText(iOwner, null) + "::" + iName, 1), functionalType(ret, real));
        }
        ClassFile.Method sm = null;
        for (ClassFile.Method m : ctx.cf.methods) {
            if (m.name().equals(iName) && m.desc().equals(iType)) { sm = m; break; }
        }
        if (sm == null || sm.code() == null) {
            code.line("// lambda " + iName + ": implementation not found (safe null)");
            return X.typ(X.v("null", 1), ret);
        }
        if (sm.code().ex().length > 0) {
            code.line("// lambda " + iName + ": body has exception handlers; try/finally not reconstructed (safe null)");
            return X.typ(X.v("null", 1), ret);
        }

        String[] parts = Types.method(iType);
        int nparams = parts.length - 1, ncap = nparams - declared;
        String[] over = new String[nparams];
        for (int i = 0; i < ncap; i++) over[i] = "\u0001C" + i;          // capture placeholders
        for (int i = ncap; i < nparams; i++) over[i] = "p" + (i - ncap); // declared params
        Cfg bg = Cfg.build(Insn.decode(sm.code().bytes()));
        String rendered = bg == null ? "" : body(bg, ctx.cf, sm, 0, over, ctx.siblings);
        for (int i = ncap - 1; i >= 0 && i < caps.size(); i++) {
            if (caps.get(i) != null) rendered = rendered.replace("\u0001C" + i, caps.get(i).s);
        }
        String head = lambdaParams(parts, declared, ncap);
        String one = singleReturn(rendered);
        String fty = functionalType(ret, real);
        if (one != null) return X.typ(X.v(head + " -> " + one, 1), fty);
        List<String> lines = new ArrayList<>();
        for (String l : rendered.split("\n", -1)) if (!l.isEmpty()) lines.add(l);
        if (lines.isEmpty()) return X.typ(X.v(head + " -> { }", 1), fty);
        return X.typ(X.block(head + " ->", lines, "}", 0, fty), fty);
    }

    /** {@code (Type0 p0, Type1 p1)} for the {@code declared} params, typed from the synthetic method. */
    private static String lambdaParams(String[] parts, int declared, int ncap) {
        if (declared == 0) return "()";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < declared; i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts[ncap + i]).append(" p").append(i);
        }
        return sb.append(')').toString();
    }

    /** The lone {@code return EXPR;} of a body, or null when the body is block-shaped. */
    private static String singleReturn(String rendered) {
        if (rendered.indexOf('\n') < 0) {
            String t = rendered.trim();
            if (t.startsWith("return ") && t.endsWith(";")) return t.substring(7, t.length() - 1);
        }
        return null;
    }

    // ===== allocation =====

    private void newX(int idx, Code code) {
        String internal = ctx.cf.cls(idx);
        String cn = Types.name(internal);
        if (cn.matches(".*\\$\\d+")) { // anonymous class
            ClassFile anon = sibling(internal);
            if (anon == null) {
                code.line("// new " + cn + " : anonymous class not found (safe null)");
                ctx.push(X.typ(new X("null", P_ATOM, 1, K_ANON), "Object"));
                return;
            }
            String iface = anonIface(anon);
            ctx.push(X.block("new " + iface, anonBody(anon), "}", K_ANON, iface));
            return;
        }
        if (cn.indexOf('$') >= 0) cn = cn.replace('$', '.');
        ctx.push(X.typ(new X("new " + cn, P_ATOM, 1, K_NEW), cn));
    }

    /** Reference type the anonymous class {@code anon} presents; generics when a Signature is available. */
    private String anonIface(ClassFile anon) {
        String[] sigs = Types.sigClassTypes(anon.sig);
        String base;
        if (anon.interfaces.length > 0) {
            int at = sigs != null && sigs.length - anon.interfaces.length >= 0
                    ? sigs.length - anon.interfaces.length : 0;
            base = Types.typeText(anon.interfaces[0],
                    sigs != null && sigs.length > at ? sigs[at] : null);
        } else if (anon.superName != null && !anon.superName.equals("java/lang/Object")) {
            base = Types.typeText(anon.superName, sigs != null && sigs.length > 0 ? sigs[0] : null);
        } else {
            base = "Object";
        }
        return base;
    }

    /** Member declarations of the anonymous class {@code anon}, decompiled as nested code lines. */
    private List<String> anonBody(ClassFile anon) {
        List<String> out = new ArrayList<>();
        for (ClassFile.Method m : anon.methods) {
            if (m.name().equals("<init>") || m.name().equals("<clinit>")) continue;
            if ((m.access() & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) != 0) continue;
            String[] pr = Types.method(m.desc());
            StringBuilder h = new StringBuilder(ClassFile.mods(m.access(), true));
            h.append(pr[pr.length - 1]).append(' ').append(m.name()).append('(');
            List<String> ps = new ArrayList<>();
            for (int i = 0; i < pr.length - 1; i++) ps.add(pr[i] + " arg" + i);
            h.append(String.join(", ", ps)).append(')');
            if (m.exceptions() != null && m.exceptions().length > 0) {
                List<String> exs = new ArrayList<>();
                for (String e : m.exceptions()) exs.add(Types.name(e));
                h.append(" throws ").append(String.join(", ", exs));
            }
            if (m.code() == null) { out.add(h + ";"); continue; }
            out.add(h + " {");
            Cfg eg = Cfg.build(Insn.decode(m.code().bytes()));
            if (eg != null) {
                for (String l : body(eg, anon, m, 1, null, ctx.siblings).split("\n", -1)) {
                    if (!l.isEmpty()) out.add(l);
                }
            }
            out.add("}");
        }
        return out;
    }

    private ClassFile sibling(String internal) {
        if (ctx.siblings == null) return null;
        return ctx.siblings.get(internal.replace('.', '/'));
    }

    private void newarray(int atype) {
        X n = ctx.pop();
        String t = switch (atype) {
            case 4 -> "boolean";
            case 5 -> "char";
            case 6 -> "float";
            case 7 -> "double";
            case 8 -> "byte";
            case 9 -> "short";
            case 10 -> "int";
            case 11 -> "long";
            default -> "Object";
        };
        ctx.push(X.v("new " + t + "[" + n.s + "]", 1));
    }

    private void anewarray(int idx, Code code) {
        X n = ctx.pop();
        ctx.push(X.v("new " + clsName(idx) + "[" + n.s + "]", 1));
    }

    private void multianewarray(Insn in) {
        int dims = in.con;
        List<X> sizes = new ArrayList<>();
        for (int i = 0; i < dims; i++) sizes.add(ctx.pop());
        Collections.reverse(sizes);
        String base = Types.type(ctx.cf.cls(in.cpool));
        for (int i = 0; i < dims; i++) {
            int k = base.lastIndexOf("[]");
            if (k >= 0) base = base.substring(0, k);
        }
        StringBuilder sb = new StringBuilder(base);
        for (X s : sizes) sb.append("[").append(s.s).append("]");
        ctx.push(X.v(sb.toString(), 1));
    }

    private String clsName(int idx) {
        String n = ctx.cf.cls(idx);
        return n.startsWith("[") ? Types.type(n) : Types.name(n);
    }

    private static String convType(int opx) {
        return switch (opx) {
            case Insn.I2L, Insn.F2L, Insn.D2L -> "long";
            case Insn.L2I, Insn.F2I, Insn.D2I -> "int";
            case Insn.L2F, Insn.I2F, Insn.D2F -> "float";
            case Insn.L2D, Insn.I2D, Insn.F2D -> "double";
            case Insn.I2B -> "byte";
            case Insn.I2C -> "char";
            default -> "short";
        };
    }

    // ===== branches =====

    private X branchOf(Insn in) {
        return branchOf(in, false);
    }

    private X branchOf(Insn in, boolean negate) {
        return switch (in.op) {
            case Insn.IFEQ, Insn.IFNE, Insn.IFLT, Insn.IFGE, Insn.IFGT, Insn.IFLE ->
                    cmp0(ctx.pop(), intCond(in.op, negate));
            case Insn.IF_ICMPEQ, Insn.IF_ICMPNE, Insn.IF_ICMPLT, Insn.IF_ICMPGE, Insn.IF_ICMPGT,
                    Insn.IF_ICMPLE -> { X b = ctx.pop(); X a = ctx.pop(); yield X.bin(a, icmpStr(in.op, negate), P_EQ, b); }
            case Insn.IF_ACMPEQ, Insn.IF_ACMPNE -> { X b = ctx.pop(); X a = ctx.pop();
                    yield X.bin(a, negate != (in.op == Insn.IF_ACMPEQ) ? "==" : "!=", P_EQ, b); }
            case Insn.IFNULL, Insn.IFNONNULL ->
                    cmp0(ctx.pop(), negate ? (in.op == Insn.IFNULL ? "!= null" : "== null")
                            : (in.op == Insn.IFNULL ? "== null" : "!= null"));
            default -> X.v("?", 1);
        };
    }

    private static String icmpStr(int opx, boolean negate) {
        return switch (opx) {
            case Insn.IF_ICMPEQ -> negate ? "!=" : "==";
            case Insn.IF_ICMPNE -> negate ? "==" : "!=";
            case Insn.IF_ICMPLT -> negate ? ">=" : "<";
            case Insn.IF_ICMPGE -> negate ? "<" : ">=";
            case Insn.IF_ICMPGT -> negate ? "<=" : ">";
            default -> negate ? ">" : "<=";
        };
    }

    private static String intCond(int opx, boolean negate) {
        return switch (opx) {
            case Insn.IFEQ -> negate ? "!= 0" : "== 0";
            case Insn.IFNE -> negate ? "== 0" : "!= 0";
            case Insn.IFLT -> negate ? ">= 0" : "< 0";
            case Insn.IFGE -> negate ? "< 0" : ">= 0";
            case Insn.IFGT -> negate ? "<= 0" : "> 0";
            default -> negate ? "> 0" : "<= 0";
        };
    }

    private static X cmp0(X v, String tail) {
        if ("boolean".equals(v.ty) && tail.startsWith("==")) return X.neg(v);
        if ("boolean".equals(v.ty)) return new X(v.s, P_ATOM, v.w, 0, "boolean");
        int p = tail.startsWith("==") || tail.startsWith("!=") ? P_EQ : P_REL;
        return new X(X.wrap(v, p, false) + " " + tail, p, 1, 0);
    }

    // ===== dup/pop/swap =====

    private void dupX1() {
        X a = ctx.pop(), b = ctx.pop();
        ctx.push(X.dup(a)); ctx.push(b); ctx.push(a);
    }

    private void dupX2() {
        X a = ctx.pop(), b = ctx.pop(), c = ctx.pop();
        ctx.push(a); ctx.push(c); ctx.push(b); ctx.push(a);
    }

    private void dup2() {
        X top = ctx.peek();
        if (top.w == 2) { ctx.push(X.dup(top)); return; }
        X a = ctx.pop(), b = ctx.pop();
        ctx.push(X.dup(b)); ctx.push(X.dup(a));
    }

    private void dup2x1() {
        X a = ctx.pop(), b = ctx.pop(), c = ctx.pop();
        ctx.push(X.dup(b)); ctx.push(X.dup(a));
        ctx.push(c); ctx.push(b); ctx.push(a);
    }

    private void dup2x2() {
        X a = ctx.pop(), b = ctx.pop(), c = ctx.pop(), d = ctx.pop();
        ctx.push(X.dup(b)); ctx.push(X.dup(a));
        ctx.push(d); ctx.push(c); ctx.push(b); ctx.push(a);
    }

    private void popDead(Code code) {
        X x = ctx.pop();
        if (x.kind == K_NEW && !ctx.st.isEmpty() && ctx.st.get(ctx.size() - 1) == x) {
            ctx.st.remove(ctx.size() - 1);
            code.line(x.s + ";");
        } else if (stmtLike(x.s)) {
            code.line((x.s.endsWith(";") ? x.s : x.s + ";"));
        }
    }

    private void popDead2(Code code) {
        X top = ctx.peek();
        if (top.w == 2) popDead(code);
        else { popDead(code); popDead(code); }
    }

    /** Emits the remaining stack entries that have side effects and clears the stack. */
    private void flushLeftover(Code code) {
        List<X> live = new ArrayList<>(ctx.st);
        for (X x : live) if (stmtLike(x.s)) code.line(x.s.endsWith(";") ? x.s : x.s + ";");
        ctx.st.clear();
    }

    private static boolean stmtLike(String s) {
        return s.contains("(") && !s.startsWith("(");
    }

    // ===== structured constructs =====

    /** Simulates the non-branch instructions of a head/trailer block, yielding its condition/key. */
    private X condBlock(int bb, Code code) {
        return condBlock(bb, code, false);
    }

    private X condBlock(int bb, Code code, boolean negate) {
        int start = ctx.g.bbs.get(bb).start(), end = ctx.g.bbs.get(bb).end();
        for (int i = start; i < end; i++) sim(ctx.g.all.get(i), code);
        Insn last = ctx.g.all.get(end);
        if (last.op == Insn.TABLESWITCH || last.op == Insn.LOOKUPSWITCH) return ctx.pop();
        return branchOf(last, negate);
    }

    private void ifter(Struc.If iff, Code code) {
        X c = condBlock(iff.head(), code);
        int base = ctx.st.size();
        int savedRegion = ctx.regionBase;
        ctx.regionBase = base;
        List<X> pre = List.copyOf(ctx.st);

        int[] t = net(iff.then()), f = net(iff.els());
        boolean tTerm = t[1] == 1, fTerm = f[1] == 1;
        int carry = 0;
        if (iff.join() >= 0) {
            if (!tTerm && !fTerm && t[0] == f[0] && t[0] > 0) carry = t[0];
            else if (tTerm && !fTerm && f[0] > 0) carry = f[0];
            else if (!tTerm && fTerm && t[0] > 0) carry = t[0];
        }

        boolean canTernary = carry == 1 && !tTerm && !fTerm && iff.join() >= 0;

        if (canTernary) {
            String type = carryTypes(iff.join(), 1)[0];

            ctx.reset(pre);
            doSeq(new Struc.Seq(iff.then()), new Code(0));
            X thenVal = ctx.st.isEmpty() ? X.v("null", 1) : ctx.pop();

            ctx.reset(pre);
            doSeq(new Struc.Seq(iff.els()), new Code(0));
            X elseVal = ctx.st.isEmpty() ? X.v("null", 1) : ctx.pop();

            if (!compatibleForTernary(thenVal, elseVal, type)) {
                canTernary = false;
            } else {
                String cond = c.collapsed();
                String tval = normalizeForType(thenVal.collapsed(), type);
                String fval = normalizeForType(elseVal.collapsed(), type);
                String ternStr = X.wrap(new X(cond, P_TERN, 1, 0), P_TERN, false)
                        + " ? " + X.wrap(new X(tval, P_TERN, 1, 0), P_TERN, true)
                        + " : " + X.wrap(new X(fval, P_TERN, 1, 0), P_TERN, true);
                X tern = new X(ternStr, P_TERN, 1, 0, type);
                ctx.push(tern);
                return;
            }
        }

        if (carry > 0) {
            String[] ts = carryTypes(iff.join(), carry);
            for (int k = 0; k < carry; k++) ctx.recordTemp(base + k, ts[carry - 1 - k]);
        }
        if (iff.join() >= 0) ctx.suppress.add(iff.join());

        code.line("if (" + c.s + ") {");
        Code sub = new Code(code.ind + 1);
        ctx.reset(pre);
        doSeq(new Struc.Seq(iff.then()), sub);
        if (carry > 0 && !tTerm) materializeCarried(carry, sub);
        code.join(sub);
        code.line("}");

        if (!iff.els().isEmpty() || (carry > 0 && !fTerm)) {
            code.line("else {");
            Code sub2 = new Code(code.ind + 1);
            ctx.reset(pre);
            doSeq(new Struc.Seq(iff.els()), sub2);
            if (carry > 0 && !fTerm) materializeCarried(carry, sub2);
            code.join(sub2);
            code.line("}");
        }

        if (iff.join() >= 0) ctx.suppress.remove(ctx.suppress.size() - 1);
        ctx.regionBase = savedRegion;
        if (carry > 0) {
            ctx.reset(pre);
            for (int k = 0; k < carry; k++) ctx.push(X.v("$t" + (base + k), 1));
        }
    }

    private void looper(Struc.Loop lp, Code code) {
        int base = ctx.st.size();
        List<X> pre = List.copyOf(ctx.st);
        int savedRegion = ctx.regionBase;
        ctx.regionBase = base;
        ctx.loops.push(new Lf(lp.head(), lp.kind() == Struc.DO ? lp.trailer() : -1, lp.exits()));

        ctx.reset(pre);
        switch (lp.kind()) {
            case Struc.WHILE -> {
                X c = condBlock(lp.head(), code, true); // iterate on the cond's fall-through
                code.line("while (" + c.s + ") {");
                Code sub = new Code(code.ind + 1);
                doSeq(new Struc.Seq(lp.body()), sub);
                sub.dropTrailingContinue();
                code.join(sub);
                code.line("}");
            }
            case Struc.DO -> {
                code.line("do {");
                Code sub = new Code(code.ind + 1);
                doSeq(new Struc.Seq(lp.body()), sub);
                sub.dropTrailingContinue();
                code.join(sub);
                X c = lp.trailer() == lp.head()
                        ? branchOf(ctx.g.all.get(ctx.g.bbs.get(lp.trailer()).end()))
                        : condBlock(lp.trailer(), code);
                code.line("} while (" + c.s + ");");
            }
            default -> {
                code.line("while (true) {");
                Code sub = new Code(code.ind + 1);
                doSeq(new Struc.Seq(lp.body()), sub);
                sub.dropTrailingContinue();
                code.join(sub);
                code.line("}");
            }
        }
        ctx.loops.pop();
        ctx.regionBase = savedRegion;
        ctx.reset(pre);
    }

    private void switcher(Struc.Sw sw, Code code) {
        X key = condBlock(sw.head(), code);
        code.line("switch (" + key.s + ") {");
        ctx.loops.push(new Lf(-1, -1, new int[]{sw.join()}));
        int labelInd = code.ind + 1;
        for (Struc.Case c : sw.cases()) {
            StringBuilder label = new StringBuilder();
            if (c.dflt()) label.append("default:");
            else for (int k : c.keys()) label.append("case ").append(k).append(":");
            code.line(label.toString());
            Code sub = new Code(labelInd + 1);
            doSeq(new Struc.Seq(c.body()), sub);
            code.join(sub);
        }
        ctx.loops.pop();
        code.line("}");
    }

    // ===== static stack deltas =====

    /** Static net stack effect (carry, term) of a statement list. */
    private int[] net(List<Struc.S> stmts) {
        int carry = 0, term = 0;
        for (Struc.S s : stmts) {
            if (s instanceof Struc.Blk b) {
                int e = ctx.g.bbs.get(b.bb()).end();
                carry += netPush(b.bb());
                int lop = ctx.g.all.get(e).op;
                if (isTerminal(lop)) term = 1;
            } else if (s instanceof Struc.Seq q) {
                int[] r = net(q.s());
                carry += r[0];
                term |= r[1];
            }
        }
        return new int[]{carry, term};
    }

    private static boolean isTerminal(int opx) {
        return (opx >= Insn.IRETURN && opx <= Insn.RETURN) || opx == Insn.ATHROW;
    }

    private int netPush(int bb) {
        int n = 0;
        int start = ctx.g.bbs.get(bb).start(), end = ctx.g.bbs.get(bb).end();
        for (int i = start; i <= end; i++) n += delta(ctx.g.all.get(i));
        return n;
    }

    private int delta(Insn in) {
        int opx = in.op;
        if ((opx >= Insn.ICONST_M1 && opx <= Insn.ICONST_5) || opx == Insn.BIPUSH || opx == Insn.SIPUSH
                || opx == Insn.FCONST_0 || opx == Insn.FCONST_1 || opx == Insn.FCONST_2
                || opx == Insn.ACONST_NULL || opx == Insn.LDC || opx == Insn.LDC_W) return 1;
        if (opx == Insn.LCONST_0 || opx == Insn.LCONST_1 || opx == Insn.DCONST_0
                || opx == Insn.DCONST_1 || opx == Insn.LDC2_W) return 2;
        if (opx >= Insn.ILOAD && opx <= Insn.ALOAD)
            return (opx == Insn.LLOAD || opx == Insn.DLOAD) ? 2 : 1;
        if (opx >= Insn.ILOAD_0 && opx <= Insn.ALOAD_3)
            return (opx >= Insn.LLOAD_0 && opx <= Insn.LLOAD_3
                    || opx >= Insn.DLOAD_0 && opx <= Insn.DLOAD_3) ? 2 : 1;
        if (opx >= Insn.ISTORE && opx <= Insn.ASTORE)
            return (opx == Insn.LSTORE || opx == Insn.DSTORE) ? -2 : -1;
        if (opx >= Insn.ISTORE_0 && opx <= Insn.ASTORE_3)
            return (opx >= Insn.LSTORE_0 && opx <= Insn.LSTORE_3
                    || opx >= Insn.DSTORE_0 && opx <= Insn.DSTORE_3) ? -2 : -1;
        if (opx == Insn.NOP || opx == Insn.IINC) return 0;
        if (opx >= Insn.IADD && opx <= Insn.DREM) return -1;
        if (opx >= Insn.INEG && opx <= Insn.DNEG) return 0;
        if (opx >= Insn.ISHL && opx <= Insn.LXOR) return -1;
        if (opx >= Insn.I2L && opx <= Insn.I2S) return 0;
        if (opx >= Insn.LCMP && opx <= Insn.DCMPG) return -1;
        if (opx >= Insn.IRETURN && opx <= Insn.RETURN)
            return switch (opx) {
                case Insn.LRETURN, Insn.DRETURN -> -2;
                case Insn.RETURN -> 0;
                default -> -1;
            };
        if (opx == Insn.ATHROW) return -1;
        if (opx >= Insn.IALOAD && opx <= Insn.SALOAD) return 0;
        if (opx >= Insn.IASTORE && opx <= Insn.SASTORE) return -3;
        if (opx == Insn.POP) return -1;
        if (opx == Insn.POP2) return -2;
        if (opx == Insn.DUP || opx == Insn.DUP_X1 || opx == Insn.DUP_X2) return 1;
        if (opx == Insn.DUP2 || opx == Insn.DUP2_X1 || opx == Insn.DUP2_X2) return 2;
        if (opx == Insn.SWAP) return 0;
        if (opx == Insn.GETSTATIC) return 1;
        if (opx == Insn.PUTSTATIC) return -1;
        if (opx == Insn.GETFIELD) return 0;
        if (opx == Insn.PUTFIELD) return -2;
        if (opx == Insn.NEW) return 1;
        if (opx == Insn.NEWARRAY || opx == Insn.ANEWARRAY) return 0;
        if (opx == Insn.MULTIANEWARRAY) return 1 - in.con;
        if (opx == Insn.ARRAYLENGTH) return 0;
        if (opx == Insn.CHECKCAST || opx == Insn.INSTANCEOF) return 0;
        if (opx == Insn.MONITORENTER || opx == Insn.MONITOREXIT) return -1;
        if (opx == Insn.INVOKEDYNAMIC) return 0;
        if (opx >= Insn.INVOKEVIRTUAL && opx <= Insn.INVOKESTATIC) {
            String[] pr = Types.method(ctx.cf.refType(in.cpool));
            int ret = Types.width(pr[pr.length - 1]);
            int args = 0;
            for (int i = 0; i < pr.length - 1; i++) args += Types.width(pr[i]);
            return ret - args - (opx == Insn.INVOKESTATIC ? 0 : 1);
        }
        return 0;
    }

    /** Types (top-first) of the {@code n} values consumed at the start of block {@code bb}. */
    private String[] carryTypes(int bb, int n) {
        String[] out = new String[n];
        java.util.Arrays.fill(out, "int");
        if (bb < 0) return out;
        boolean[] done = new boolean[n];
        int remaining = n;
        int pending = 0; // pushes above the carried tokens
        int start = ctx.g.bbs.get(bb).start(), end = ctx.g.bbs.get(bb).end();
        for (int i = start; i <= end && remaining > 0; i++) {
            Insn in = ctx.g.all.get(i);
            String[] pops = popsOf(in);
            int tokSeen = 0;
            for (int p = 0; p < pops.length && remaining > 0; p++) {
                if (pending > 0) { pending--; continue; }
                int token = n - 1 - tokSeen;
                if (token >= 0) {
                    done[token] = true;
                    remaining--;
                    if (!pops[p].equals("?")) out[token] = pops[p];
                    tokSeen++;
                }
            }
            pending = Math.max(0, pending + delta(in) + pops.length);
        }
        return out;
    }

    /** Java types of the values popped by {@code in}, top-first; "?" = unknown as primitive. */
    private String[] popsOf(Insn in) {
        int opx = in.op;
        if (opx >= Insn.IRETURN && opx <= Insn.ARETURN)
            return new String[]{switch (opx) {
                case Insn.LRETURN -> "long";
                case Insn.FRETURN -> "float";
                case Insn.DRETURN -> "double";
                case Insn.ARETURN -> ctx.mret;
                case Insn.IRETURN -> ctx.mret;
                default -> "int";
            }};
        if (opx >= Insn.ISTORE && opx <= Insn.ASTORE)
            return new String[]{switch (opx) {
                case Insn.LSTORE -> "long";
                case Insn.FSTORE -> "float";
                case Insn.DSTORE -> "double";
                case Insn.ASTORE -> "Object";
                default -> "int";
            }};
        if (opx >= Insn.IADD && opx <= Insn.DREM) {
            String t = switch (opx) {
                case Insn.LADD, Insn.LSUB, Insn.LMUL, Insn.LDIV, Insn.LREM -> "long";
                case Insn.FADD, Insn.FSUB, Insn.FMUL, Insn.FDIV, Insn.FREM -> "float";
                case Insn.DADD, Insn.DSUB, Insn.DMUL, Insn.DDIV, Insn.DREM -> "double";
                default -> "int";
            };
            return new String[]{t, t};
        }
        if (opx == Insn.ISHL || opx == Insn.ISHR || opx == Insn.IUSHR) return new String[]{"int", "int"};
        if (opx == Insn.LSHL || opx == Insn.LSHR || opx == Insn.LUSHR) return new String[]{"int", "long"};
        if (opx == Insn.IAND || opx == Insn.IOR || opx == Insn.IXOR) return new String[]{"int", "int"};
        if (opx == Insn.LAND || opx == Insn.LOR || opx == Insn.LXOR) return new String[]{"long", "long"};
        if (opx >= Insn.INEG && opx <= Insn.DNEG) return new String[]{
                switch (opx) {
                    case Insn.LNEG -> "long";
                    case Insn.FNEG -> "float";
                    case Insn.DNEG -> "double";
                    default -> "int";
                }};
        if (opx >= Insn.LCMP && opx <= Insn.DCMPG) {
            String t = (opx >= Insn.FCMPL && opx <= Insn.FCMPG) ? "float"
                    : (opx >= Insn.DCMPL) ? "double" : "long";
            return new String[]{t, t};
        }
        if (opx >= Insn.IFEQ && opx <= Insn.IFLE) return new String[]{"int"};
        if (opx >= Insn.IF_ICMPEQ && opx <= Insn.IF_ICMPLE) return new String[]{"int", "int"};
        if (opx >= Insn.IF_ACMPEQ && opx <= Insn.IF_ACMPNE) return new String[]{"Object", "Object"};
        if (opx == Insn.IFNULL || opx == Insn.IFNONNULL) return new String[]{"Object"};
        if (opx >= Insn.IASTORE && opx <= Insn.SASTORE)
            return new String[]{switch (opx) {
                case Insn.LASTORE -> "long";
                case Insn.FASTORE -> "float";
                case Insn.DASTORE -> "double";
                case Insn.AASTORE -> "Object";
                case Insn.BASTORE -> "byte";
                case Insn.CASTORE -> "char";
                case Insn.SASTORE -> "short";
                default -> "int";
            }, "int", "Object"};
        if (opx == Insn.PUTFIELD) return new String[]{"?", "?"};
        if (opx == Insn.PUTSTATIC) return new String[]{"?"};
        if (opx == Insn.POP) return new String[]{"int"};
        if (opx == Insn.POP2) return new String[]{"int"};
        if (opx >= Insn.INVOKEVIRTUAL && opx <= Insn.INVOKEDYNAMIC) {
            String[] pr;
            try {
                pr = opx == Insn.INVOKEDYNAMIC
                        ? Types.method(ctx.cf.natType(((ClassFile.Cv) ctx.cf.cp[in.cpool]).nat()))
                        : Types.method(ctx.cf.refType(in.cpool));
            } catch (RuntimeException e) { return new String[0]; }
            String[] pops = new String[pr.length - 1 + (opx == Insn.INVOKESTATIC ? 0 : 1)];
            for (int i = pr.length - 2; i >= 0; i--) pops[pr.length - 2 - i] = pr[i];
            if (opx != Insn.INVOKESTATIC) pops[pops.length - 1] = "Object";
return pops;
        }
        return new String[0];
    }

    private static boolean compatibleForTernary(X a, X b, String expectedType) {
        String ta = a.ty != null ? a.ty : inferTypeFromValue(a.s);
        String tb = b.ty != null ? b.ty : inferTypeFromValue(b.s);
        if ("boolean".equals(expectedType)) {
            return isBooleanCompatible(ta) && isBooleanCompatible(tb);
        }
        return ta.equals(tb) || isNumeric(ta) && isNumeric(tb);
    }

    private static boolean isBooleanCompatible(String t) {
        return "boolean".equals(t) || "int".equals(t);
    }

    private static boolean isNumeric(String t) {
        return "byte".equals(t) || "short".equals(t) || "int".equals(t) || "long".equals(t)
                || "float".equals(t) || "double".equals(t);
    }

    private static String inferTypeFromValue(String s) {
        if (s.equals("true") || s.equals("false")) return "boolean";
        if (s.matches("-?\\d+")) return "int";
        if (s.matches("-?\\d+L")) return "long";
        if (s.matches("-?\\d+\\.\\d*[fF]")) return "float";
        if (s.matches("-?\\d+\\.\\d*[dD]?")) return "double";
        if (s.equals("null")) return "Object";
        return "Object";
    }

    private static String normalizeForType(String val, String type) {
        if ("boolean".equals(type)) {
            if (val.equals("0") || val.equals("1")) return val.equals("0") ? "false" : "true";
            if (val.equals("true") || val.equals("false")) return val;
        }
        return val;
    }
}