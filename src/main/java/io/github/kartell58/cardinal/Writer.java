package io.github.kartell58.cardinal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a parsed {@link ClassFile} into Java source text: package, class header, fields, methods and,
 * when sibling class files are available, nested classes. Method bodies come from {@link Sim}.
 */
final class Writer {

    private Writer() {}

    static String write(ClassFile cf) {
        return write(cf, null);
    }

    static String write(ClassFile cf, Map<String, ClassFile> siblings) {
        StringBuilder sb = new StringBuilder();
        String dotted = Types.name(cf.name);
        int p = dotted.lastIndexOf('.');
        if (p > 0) sb.append("package ").append(dotted, 0, p).append(";\n\n");
        renderClass(cf, sb, 0, siblings, cf.name);
        return sb.toString();
    }

    private static void renderClass(ClassFile cf, StringBuilder sb, int ind, Map<String, ClassFile> siblings,
            String topInternal) {
        String pad = "  ".repeat(ind);
        boolean isInterface = (cf.access & ClassFile.ACC_INTERFACE) != 0;
        String dotted = Types.name(cf.name);
        String simple = dotted.substring(Math.max(dotted.lastIndexOf('.'), dotted.lastIndexOf('$')) + 1);
        String[] clsSig = Types.sigClassTypes(cf.sig);
        sb.append(pad).append(ClassFile.mods(cf.access, false))
                .append(isInterface ? "interface " : "class ").append(simple)
                .append(Types.typeParams(cf.sig));
        if (!isInterface && cf.superName != null && !cf.superName.equals("java/lang/Object"))
            sb.append(" extends ").append(Types.typeText(cf.superName,
                    clsSig != null && clsSig.length > 0 ? clsSig[0] : null));
        if (cf.interfaces.length > 0) {
            sb.append(isInterface ? " extends " : " implements ");
            List<String> xs = new ArrayList<>();
            for (int i = 0; i < cf.interfaces.length; i++) {
                String t = clsSig != null && clsSig.length >= i + 2 ? clsSig[i + 1] : null;
                xs.add(Types.typeText(cf.interfaces[i], t));
            }
            sb.append(String.join(", ", xs));
        }
        sb.append(" {\n");

        for (ClassFile.Field f : cf.fields) {
            if ((f.access() & ClassFile.ACC_SYNTHETIC) != 0) continue;
            sb.append(pad).append("  ").append(ClassFile.mods(f.access(), false));
            String ft = f.sig() != null ? Types.sig(f.sig()) : null;
            sb.append(ft == null ? Types.field(f.desc()) : ft).append(' ').append(f.name());
            if (f.cv() != null) sb.append(" = ").append(f.cv());
            sb.append(";\n");
        }

        for (ClassFile.Method m : cf.methods) {
            if ((m.access() & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) != 0) continue;
            sb.append('\n');
            if (m.name().equals("<clinit>")) {
                sb.append(pad).append("  static {\n").append(body(m, cf, ind + 2, siblings)).append(pad).append("  }\n");
                continue;
            }
            if (m.name().equals("<init>")) {
                sb.append(pad).append("  ").append(ClassFile.mods(m.access(), true)).append(simple).append(params(m))
                        .append(" {\n").append(body(m, cf, ind + 2, siblings)).append(pad).append("  }\n");
                continue;
            }
            String sig = sigOf(m);
            if ((m.access() & (ClassFile.ACC_ABSTRACT | ClassFile.ACC_NATIVE)) != 0) {
                sb.append(pad).append("  ").append(sig).append(";\n");
            } else {
                sb.append(pad).append("  ").append(sig).append(" {\n").append(body(m, cf, ind + 2, siblings))
                        .append(pad).append("  }\n");
            }
        }

        if (siblings != null) {
            List<ClassFile> inners = new ArrayList<>();
            String prefix = cf.name + "$";
            for (ClassFile s : siblings.values()) {
                if (s.name.startsWith(prefix) && !s.name.equals(cf.name)
                        && (s.access & ClassFile.ACC_SYNTHETIC) == 0
                        && !s.name.matches(".*\\$\\d+"))
                    inners.add(s);
            }
            inners.sort(java.util.Comparator.comparing(a -> a.name));
            for (ClassFile inner : inners) {
                sb.append('\n');
                renderClass(inner, sb, ind + 1, siblings, topInternal);
            }
        }
        sb.append(pad).append("}\n");
    }

    private static String sigOf(ClassFile.Method m) {
        String[] sg = Types.sigMethod(m.sig());
        String[] pr = Types.method(m.desc());
        String ret = sg != null ? sg[sg.length - 1] : pr[pr.length - 1];
        String tp = Types.typeParams(m.sig());
        String sig = ClassFile.mods(m.access(), true) + (tp.isEmpty() ? "" : tp + " ")
                + ret + " " + m.name() + params(m);
        if (m.exceptions() != null && m.exceptions().length > 0) {
            List<String> exs = new ArrayList<>();
            for (String e : m.exceptions()) exs.add(Types.name(e));
            sig += " throws " + String.join(", ", exs);
        }
        return sig;
    }

    private static String params(ClassFile.Method m) {
        String[] sg = Types.sigMethod(m.sig());
        String[] pr = Types.method(m.desc());
        List<String> ps = new ArrayList<>();
        for (int i = 0; i < pr.length - 1; i++) {
            String t = sg != null ? sg[i] : pr[i];
            ps.add(t + " arg" + i);
        }
        return "(" + String.join(", ", ps) + ")";
    }

    private static String body(ClassFile.Method m, ClassFile cf, int indent, Map<String, ClassFile> siblings) {
        if (m.code() == null) return "";
        Cfg g = Cfg.build(Insn.decode(m.code().bytes()));
        String s = Sim.body(g, cf, m, indent, null, siblings);
        if ("<clinit>".equals(m.name())) {
            String pad = "  ".repeat(indent);
            List<String> ls = new ArrayList<>(java.util.Arrays.asList(s.split("\n")));
            while (!ls.isEmpty() && ls.get(ls.size() - 1).trim().equals("return;")) ls.remove(ls.size() - 1);
            s = String.join("\n", ls);
        }
        return s.isEmpty() ? "" : s + "\n";
    }
}