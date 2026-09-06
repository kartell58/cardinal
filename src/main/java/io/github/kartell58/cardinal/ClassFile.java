package io.github.kartell58.cardinal;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, clean-room JVM class file reader. Parses only the attributes a decompiler needs:
 * Code, Exceptions, BootstrapMethods, LocalVariableTable, Signature, SourceFile. Everything else is skipped.
 */
final class ClassFile {

    static final int ACC_PUBLIC = 0x0001, ACC_PRIVATE = 0x0002, ACC_PROTECTED = 0x0004,
            ACC_STATIC = 0x0008, ACC_FINAL = 0x0010, ACC_SYNC = 0x0020, ACC_SUPER = 0x0020,
            ACC_VOLATILE = 0x0040, ACC_BRIDGE = 0x0040, ACC_TRANSIENT = 0x0080, ACC_VARARGS = 0x0080,
            ACC_NATIVE = 0x0100, ACC_INTERFACE = 0x0200, ACC_ABSTRACT = 0x0400, ACC_STRICT = 0x0800,
            ACC_SYNTHETIC = 0x1000, ACC_ANNOTATION = 0x2000, ACC_ENUM = 0x4000;

    final int access;
    final String name;
    final String superName;
    final String[] interfaces;
    final String sig;
    final List<Field> fields;
    final List<Method> methods;
    final Cp[] cp;
    final int[][] bootstrap;
    final String sourceFile;

    record Field(int access, String name, String desc, String cv, String sig) {}

    record Method(int access, String name, String desc, Code code, String[] exceptions, String sig) {}

    record Code(int maxLocals, byte[] bytes, Ex[] ex, Lvt[] lvt) {}

    record Ex(int start, int end, int handler, int catchType) {}

    record Lvt(int start, int len, String name, String desc, int index) {}

    /** Constant pool entries (index 1-based; entry 0 is null). */
    sealed interface Cp {}

    record Cu(String v) implements Cp {}                 // 1  Utf8
    record Ci(int v) implements Cp {}                    // 3  Integer
    record Cf(float v) implements Cp {}                  // 4  Float
    record Cl(long v) implements Cp {}                   // 5  Long
    record Cd(double v) implements Cp {}                 // 6  Double
    record Cc(int name) implements Cp {}                 // 7  Class
    record Cs(int str) implements Cp {}                  // 8  String
    record Cr(int tag, int cls, int nat) implements Cp {}// 9/10/11 Ref
    record Cn(int name, int desc) implements Cp {}       // 12 NameAndType
    record Cm(int kind, int ref) implements Cp {}        // 15 MethodHandle
    record Ct(int desc) implements Cp {}                 // 16 MethodType
    record Cv(int tag, int bs, int nat) implements Cp {} // 17/18 Dynamic/InvokeDynamic
    record Cmod(int tag, int name) implements Cp {}      // 19/20 Module/Package

    private final byte[] b;
    private int p;

    private ClassFile(byte[] bytes) {
        b = bytes;
        p = 0;
        if (u4() != 0xcafebabe) throw new IllegalArgumentException("bad magic");
        u2();
        int major = u2();
        int cpc = u2();
        cp = new Cp[cpc];
        for (int i = 1; i < cpc; i++) {
            cp[i] = readCp();
            if (cp[i] instanceof Cl || cp[i] instanceof Cd) cp[++i] = null;
        }
        access = u2();
        name = cls(u2());
        int sup = u2();
        superName = sup == 0 ? null : cls(sup);
        int ic = u2();
        interfaces = new String[ic];
        for (int i = 0; i < ic; i++) interfaces[i] = cls(u2());
        int fc = u2();
        fields = new ArrayList<>();
        for (int i = 0; i < fc; i++) {
            int fa = u2();
            String fn = utf(u2());
            String fd = utf(u2());
            String cv = null;
            String fsig = null;
            for (int n = u2(); n > 0; n--) {
                String an = utf(u2());
                int len = u4();
                int saved = p + len;
                if (an.equals("ConstantValue")) cv = constText(u2());
                else if (an.equals("Signature")) fsig = utf(u2());
                p = saved;
            }
            fields.add(new Field(fa, fn, fd, cv, fsig));
        }
        int mc = u2();
        methods = new ArrayList<>();
        for (int i = 0; i < mc; i++) {
            int ma = u2();
            String mn = utf(u2());
            String md = utf(u2());
            Code code = null;
            String[] exns = new String[0];
            String msig = null;
            for (int n = u2(); n > 0; n--) {
                String an = utf(u2());
                int len = u4();
                int saved = p + len;
                if (an.equals("Signature")) msig = utf(u2());
                else if (an.equals("Code")) {
                    u2();
                    int ml = u2();
                    int cl = u4();
                    byte[] cb = new byte[cl];
                    System.arraycopy(b, p, cb, 0, cl);
                    p += cl;
                    int ec = u2();
                    Ex[] exArr = new Ex[ec];
                    for (int e = 0; e < ec; e++) exArr[e] = new Ex(u2(), u2(), u2(), u2());
                    List<Lvt> lvt = new ArrayList<>();
                    for (int a = u2(); a > 0; a--) {
                        String an2 = utf(u2());
                        int al = u4();
                        int aend = p + al;
                        if (an2.equals("LocalVariableTable")) {
                            for (int v = u2(); v > 0; v--) {
                                lvt.add(new Lvt(u2(), u2(), utf(u2()), utf(u2()), u2()));
                            }
                        }
                        p = aend;
                    }
                    code = new Code(ml, cb, exArr, lvt.toArray(new Lvt[0]));
                } else if (an.equals("Exceptions")) {
                    int cnt = u2();
                    String[] xs = new String[cnt];
                    for (int e = 0; e < cnt; e++) xs[e] = cls(u2());
                    exns = xs;
                } else p += len;
                p = saved;
            }
            methods.add(new Method(ma, mn, md, code, exns, msig));
        }
        int[][] bs = null;
        String sf = null;
        String cs = null;
        for (int n = u2(); n > 0; n--) {
            String an = utf(u2());
            int len = u4();
            if (an.equals("Signature")) {
                cs = utf(u2());
                p += len - 2;
            } else if (an.equals("BootstrapMethods")) {
                int count = u2();
                List<int[]> tmp = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int ref = u2();
                    int na = u2();
                    int[] args = new int[na + 1];
                    args[0] = ref;
                    for (int j = 0; j < na; j++) args[j + 1] = u2();
                    tmp.add(args);
                }
                bs = tmp.toArray(new int[0][]);
            } else if (an.equals("SourceFile")) {
                sf = utf(u2());
                p += len - 2;
            } else p += len;
        }
        bootstrap = bs;
        sourceFile = sf;
        sig = cs;
        if (major < 45 || major > 80) throw new IllegalArgumentException("unsupported class version " + major);
    }

    static ClassFile read(byte[] bytes) {
        return new ClassFile(bytes);
    }

    private Cp readCp() {
        int tag = u1();
        return switch (tag) {
            case 1 -> {
                int len = u2();
                Cu v = new Cu(utf8(b, p, len));
                p += len;
                yield v;
            }
            case 3 -> new Ci(u4());
            case 4 -> new Cf(Float.intBitsToFloat(u4()));
            case 5 -> new Cl((long) u4() << 32 | (u4() & 0xffffffffL));
            case 6 -> new Cd(Double.longBitsToDouble((long) u4() << 32 | (u4() & 0xffffffffL)));
            case 7, 8 -> tag == 7 ? new Cc(u2()) : new Cs(u2());
            case 9, 10, 11 -> new Cr(tag, u2(), u2());
            case 12 -> new Cn(u2(), u2());
            case 15 -> new Cm(u1(), u2());
            case 16 -> new Ct(u2());
            case 17, 18 -> new Cv(tag, u2(), u2());
            case 19, 20 -> new Cmod(tag, u2());
            default -> throw new IllegalArgumentException("bad cp tag " + tag);
        };
    }

    /** Modified UTF-8 or plain UTF-8 decoder (tolerates both). */
    static String utf8(byte[] data, int off, int len) {
        StringBuilder s = new StringBuilder(len);
        for (int i = off, end = off + len; i < end; ) {
            int c = data[i] & 0xff;
            if (c == 0) { s.append('\u0000'); i++; }
            else if (c < 0x80) { s.append((char) c); i++; }
            else if ((c & 0xe0) == 0xc0) {
                s.append((char) (((c & 0x1f) << 6) | (data[i + 1] & 0x3f)));
                i += 2;
            } else if ((c & 0xf0) == 0xe0) {
                s.append((char) (((c & 0x0f) << 12) | ((data[i + 1] & 0x3f) << 6) | (data[i + 2] & 0x3f)));
                i += 3;
            } else {
                int cp = ((c & 0x07) << 18) | ((data[i + 1] & 0x3f) << 12) | ((data[i + 2] & 0x3f) << 6) | (data[i + 3] & 0x3f);
                s.appendCodePoint(cp);
                i += 4;
            }
        }
        return s.toString();
    }

    private int u1() { return b[p++] & 0xff; }

    private int u2() { int v = ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff); p += 2; return v; }

    private int u4() {
        int v = (b[p] & 0xff) << 24 | (b[p + 1] & 0xff) << 16 | (b[p + 2] & 0xff) << 8 | (b[p + 3] & 0xff);
        p += 4;
        return v;
    }

    private void skipAttrs() {
        for (int n = u2(); n > 0; n--) { utf(u2()); int len = u4(); p += len; }
    }

    String cls(int i) { return utf(((Cc) cp[i]).name()); }

    String utf(int i) { return ((Cu) cp[i]).v(); }

    String typ(int i) { return utf(((Cc) cp[i]).name()); }

    String natName(int i) { return utf(((Cn) cp[i]).name()); }

    String natType(int i) { return utf(((Cn) cp[i]).desc()); }

    String refOwner(int i) { return cls(((Cr) cp[i]).cls()); }

    String refName(int i) { return natName(((Cr) cp[i]).nat()); }

    String refType(int i) { return natType(((Cr) cp[i]).nat()); }

    /** ConstantValue entry (index of an Integer/Float/Long/Double/String constant) as a Java literal. */
    String constText(int i) {
        return switch (cp[i]) {
            case Ci x -> String.valueOf(x.v());
            case Cf x -> floatText(x.v());
            case Cl x -> x.v() + "L";
            case Cd x -> doubleText(x.v());
            case Cu x -> quote(x.v());
            default -> "?";
        };
    }

    private static String floatText(float f) {
        String s = Float.toString(f);
        return (s.endsWith("f") || s.endsWith("F") ? s : s + "f");
    }

    private static String doubleText(double d) {
        String s = Double.toString(d);
        return (s.endsWith("d") || s.endsWith("D") ? s : s + "d");
    }

    private static String quote(String s) {
        StringBuilder q = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> q.append("\\\"");
                case '\\' -> q.append("\\\\");
                case '\n' -> q.append("\\n");
                case '\t' -> q.append("\\t");
                case '\r' -> q.append("\\r");
                default -> q.append(c);
            }
        }
        return q.append('"').toString();
    }

    /** Java source modifiers for a class/field/method access int. */
    static String mods(int access, boolean method) {
        StringBuilder s = new StringBuilder();
        if ((access & ACC_PUBLIC) != 0) s.append("public ");
        else if ((access & ACC_PROTECTED) != 0) s.append("protected ");
        else if ((access & ACC_PRIVATE) != 0) s.append("private ");
        if ((access & ACC_STATIC) != 0) s.append("static ");
        if ((access & ACC_FINAL) != 0) s.append("final ");
        if (method && (access & ACC_SYNC) != 0) s.append("synchronized ");
        if (method && (access & ACC_NATIVE) != 0) s.append("native ");
        if (method && (access & ACC_ABSTRACT) != 0) s.append("abstract ");
        if (method && (access & ACC_STRICT) != 0) s.append("strictfp ");
        if (!method && (access & ACC_VOLATILE) != 0) s.append("volatile ");
        else if (!method && (access & ACC_TRANSIENT) != 0) s.append("transient ");
        return s.toString();
    }
}