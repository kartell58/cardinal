package io.github.kartell58.cardinal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns a {@link Cfg} into a structured statement tree (if/else, while, do-while, while(true), switch). */
final class Struc {

    static final int WHILE = 0, DO = 1, TRUE = 2;

    sealed interface S {}

    record Seq(List<S> s) implements S {}

    record Blk(int bb) implements S {}

    record If(int head, List<S> then, List<S> els, int join) implements S {}

    record Loop(int head, int kind, int trailer, int join, List<S> body, int[] exits) implements S {}

    record Sw(int head, List<Case> cases, int join) implements S {}

    record Case(int[] keys, boolean dflt, int entry, int last, List<S> body) implements S {}

    record R(List<S> stmts, int last) {}

    private final Cfg g;
    private final boolean[] loopHead;
    private final boolean[] walk;

    private Struc(Cfg g) {
        this.g = g;
        loopHead = new boolean[g.n];
        for (int[] e : g.backEdges) loopHead[e[1]] = true;
        walk = new boolean[g.n];
    }

    static S build(Cfg cfg) {
        if (cfg == null) return new Seq(List.of());
        return new Seq(new Struc(cfg).region(0, null, Set.of(), Set.of()).stmts);
    }

    /** Debug: renders the statement tree as indented text. */
    static String tree(S root) {
        StringBuilder sb = new StringBuilder();
        tree(root, 0, sb);
        return sb.toString();
    }

    private static void tree(S s, int ind, StringBuilder sb) {
        sb.append("  ".repeat(ind));
        switch (s) {
            case Seq q -> { sb.append("Seq\n"); for (S c : q.s()) tree(c, ind + 1, sb); }
            case Blk b -> sb.append("Blk ").append(b.bb()).append("\n");
            case If f -> { sb.append("If head=").append(f.head()).append(" join=").append(f.join()).append("\n");
                sb.append("  ".repeat(ind + 1)).append(">then\n"); for (S c : f.then()) tree(c, ind + 2, sb);
                if (!f.els().isEmpty()) { sb.append("  ".repeat(ind + 1)).append(">else\n"); for (S c : f.els()) tree(c, ind + 2, sb); } }
            case Loop l -> { sb.append("Loop head=").append(l.head()).append(" kind=")
                    .append(l.kind() == WHILE ? "WHILE" : l.kind() == DO ? "DO" : "TRUE").append(" trailer=")
                    .append(l.trailer()).append(" join=").append(l.join()).append("\n");
                for (S c : l.body()) tree(c, ind + 1, sb); }
            case Sw w -> { sb.append("Sw head=").append(w.head()).append(" join=").append(w.join()).append("\n");
                for (Case c : w.cases()) { sb.append("  ".repeat(ind + 1)).append("case ").append(java.util.Arrays.toString(c.keys()))
                        .append(" dflt=").append(c.dflt()).append("\n"); for (S x : c.body()) tree(x, ind + 2, sb); } }
            default -> sb.append(s).append("\n");
        }
    }

    private int lastOp(int bb) {
        return g.all.get(g.bbs.get(bb).end()).op;
    }

    private static boolean isCond(int op) {
        return (op >= Insn.IFEQ && op <= Insn.IF_ACMPNE) || op == Insn.IFNULL || op == Insn.IFNONNULL;
    }

    private R region(int entry, Set<Integer> loop, Set<Integer> exits, Set<Integer> active) {
        List<S> out = new ArrayList<>();
        int cur = entry;
        int last = -1;
        walk:
        while (true) {
            if (cur == -1) break walk;
            if (walk[cur]) break walk;
            if (exits.contains(cur)) break walk;
            if (loop != null && !loop.contains(cur)) break walk;
            Insn li = g.all.get(g.bbs.get(cur).end());
            switch (li.op) {
                case Insn.TABLESWITCH, Insn.LOOKUPSWITCH -> {
                    if (loopHead[cur] && !active.contains(cur)) {
                        Loop lp = buildLoop(cur, loop, exits, active);
                        walk[cur] = true;
                        last = cur;
                        out.add(lp);
                        cur = lp.join;
                    } else {
                        walk[cur] = true;
                        last = cur;
                        Sw sw = buildSwitch(cur, loop, exits, active);
                        out.add(sw);
                        cur = sw.join;
                    }
                }
                default -> {
                    if (loopHead[cur] && !active.contains(cur)) {
                        Loop lp = buildLoop(cur, loop, exits, active);
                        walk[cur] = true;
                        last = cur;
                        out.add(lp);
                        cur = lp.join;
                    } else {
                        walk[cur] = true;
                        last = cur;
                        if (g.succ[cur].length == 0) {
                            out.add(new Blk(cur));
                            break walk;
                        } else if (isCond(li.op)) {
                            if (g.succ[cur].length < 2) { out.add(new Blk(cur)); break walk; }
                            R ifr = buildIf(cur, loop, exits, active, out);
                            cur = ifr.last;
                        } else if (g.succ[cur].length == 1) {
                            int t = g.succ[cur][0];
                            out.add(new Blk(cur));
                            if (exits.contains(t) || (loop != null && !loop.contains(t))) {
                                break walk;
                            }
                            cur = t;
                        } else {
                            out.add(new Blk(cur));
                            break walk;
                        }
                    }
                }
            }
        }
        return new R(out, last);
    }

    /** Emits an if/else into out; returns the continuation block (R.last = next cur). */
    private R buildIf(int cur, Set<Integer> loop, Set<Integer> exits, Set<Integer> active, List<S> out) {
        int taken = g.succ[cur][0];
        int fall = g.succ[cur][1];
        int j = g.join(taken, fall);
        if (j == -1) {
            return divergentIf(cur, taken, fall, loop, exits, active, out);
        }
        if (!boundary(j, loop, exits)) {
            Set<Integer> bx = new HashSet<>(exits);
            bx.add(j);
            if (g.succ[taken].length == 0) {
                R tR = region(taken, loop, bx, active);
                out.add(new If(cur, tR.stmts, List.of(), j));
            } else if (g.succ[fall].length == 0) {
                R fR = region(fall, loop, bx, active);
                out.add(new If(cur, List.of(), fR.stmts, j));
            } else if (fall == j) {
                R tR = region(taken, loop, bx, active);
                out.add(new If(cur, tR.stmts, List.of(), j));
            } else if (taken == j) {
                R fR = region(fall, loop, bx, active);
                out.add(new If(cur, List.of(), fR.stmts, j));
            } else {
                R tR = region(taken, loop, bx, active);
                R fR = region(fall, loop, bx, active);
                out.add(new If(cur, tR.stmts, fR.stmts, j));
            }
            return new R(out, j);
        }
        return divergentIf(cur, taken, fall, loop, exits, active, out);
    }

    /** Arms diverge (return/break/loop-exit). Emits the cleanest if-form, then decides continuation. */
    private R divergentIf(int cur, int taken, int fall, Set<Integer> loop, Set<Integer> exits,
                          Set<Integer> active, List<S> out) {
        boolean tTerm = g.succ[taken].length == 0;
        boolean fTerm = g.succ[fall].length == 0;
        int cont = -1;
        if (tTerm && !fTerm) {
            // if (cond) return...;   continuation regains the fall path
            R tR = region(taken, loop, exits, active);
            out.add(new If(cur, tR.stmts, List.of(), -1));
            cont = fall;
        } else if (fTerm) {
            R tR = region(taken, loop, exits, active);
            R fR = region(fall, loop, exits, active);
            out.add(new If(cur, tR.stmts, fR.stmts, -1));
        } else {
            R tR = region(taken, loop, exits, active);
            R fR = region(fall, loop, exits, active);
            out.add(new If(cur, tR.stmts, fR.stmts, -1));
        }
        return new R(out, cont);
    }

    private boolean boundary(int j, Set<Integer> loop, Set<Integer> exits) {
        return exits.contains(j) || (loop != null && !loop.contains(j));
    }

    private Loop buildLoop(int h, Set<Integer> outerLoop, Set<Integer> outerExits, Set<Integer> active) {
        Set<Integer> L = naturalLoop(h);
        boolean[] in = new boolean[g.n];
        for (int b : L) in[b] = true;
        Set<Integer> exits = new LinkedHashSet<>();
        for (int b : L) for (int s : g.succ[b]) if (!in[s]) exits.add(s);
        List<Integer> backSrc = new ArrayList<>();
        for (int p : g.pred[h]) if (g.dom(h, p)) backSrc.add(p);
        List<Integer> inSucc = new ArrayList<>();
        for (int s : g.succ[h]) if (in[s]) inSucc.add(s);

        int kind;
        int trailer = -1;
        boolean hCond = isCond(lastOp(h));
        boolean selfBack = backSrc.contains(h);
        if (selfBack && hCond && g.succ[h].length == 2 && inSucc.size() == 1) {
            // header block holds body + trailing test, back-edge targets the header itself
            kind = DO;
            trailer = h;
        } else if (hCond && inSucc.size() == 1 && g.succ[h].length == 2) {
            kind = WHILE;
        } else {
            int doSrc = -1;
            if (backSrc.size() == 1) {
                int p = backSrc.get(0);
                if (isCond(lastOp(p)) && g.succ[p].length == 2) {
                    int other = g.succ[p][0] == h ? g.succ[p][1] : g.succ[p][0];
                    if (!in[other]) doSrc = p;
                }
            }
            if (doSrc != -1) { kind = DO; trailer = doSrc; }
            else kind = TRUE;
        }

        int bodyHead;
        if (kind == WHILE) bodyHead = inSucc.get(0);
        else bodyHead = h;

        Set<Integer> bodyLoop = new HashSet<>(L);
        Set<Integer> bodyExits = new HashSet<>(exits);
        if (kind == DO) {
            bodyLoop.remove(trailer);
            bodyExits.add(trailer);
        } else if (kind == WHILE) {
            bodyExits.add(h); // walking the condition block ends the loop walk
        } // TRUE: the walk starts at h and the walk[] guard ends it on the back-edge
        Set<Integer> act = new HashSet<>(active);
        act.add(h);
        R r;
        if (kind == DO && trailer == h) {
            List<S> stmts = new ArrayList<>();
            stmts.add(new Blk(h)); // merged header block: body minus its trailing test
            Set<Integer> bodyLoop2 = new HashSet<>(L);
            Set<Integer> bodyExits2 = new HashSet<>(exits);
            for (int b : L) if (b != h) {
                R rr = region(b, bodyLoop2, bodyExits2, act);
                stmts.addAll(rr.stmts);
            }
            r = new R(stmts, h);
        } else {
            r = region(bodyHead, bodyLoop, bodyExits, act);
        }

        int[] exArr = exits.stream().mapToInt(Integer::intValue).toArray();
        int join;
        if (kind == WHILE) join = nonLoopSucc(h, in);
        else if (kind == DO) join = nonLoopSucc(trailer, in);
        else join = minById(exits);
        return new Loop(h, kind, trailer, join, r.stmts, exArr);
    }

    private int nonLoopSucc(int bb, boolean[] in) {
        for (int s : g.succ[bb]) if (!in[s]) return s;
        return -1;
    }

    private int minById(Set<Integer> s) {
        int r = -1;
        for (int x : s) if (r == -1 || x < r) r = x;
        return r;
    }

    private Set<Integer> naturalLoop(int h) {
        Set<Integer> L = new LinkedHashSet<>();
        L.add(h);
        Deque<Integer> dq = new ArrayDeque<>();
        boolean[] reach = new boolean[g.n];
        for (int p : g.pred[h]) if (g.dom(h, p)) { dq.push(p); reach[p] = true; }
        while (!dq.isEmpty()) {
            int v = dq.pop();
            for (int u : g.pred[v]) {
                if (u == h) continue;
                if (!reach[u]) { reach[u] = true; dq.push(u); }
            }
        }
        for (int v = 0; v < g.n; v++) if (reach[v] && g.dom(h, v)) L.add(v);
        return L;
    }

    private Sw buildSwitch(int head, Set<Integer> loop, Set<Integer> exits, Set<Integer> active) {
        Insn li = g.all.get(g.bbs.get(head).end());
        int dflt = g.blockOfPc[li.tars[0]];
        int[] keys;
        int[] tars;
        if (li.op == Insn.TABLESWITCH) {
            keys = new int[li.high - li.low + 1];
            for (int k = 0; k < keys.length; k++) keys[k] = li.low + k;
            tars = li.tars; // tars[0]=dflt, tars[1..]
        } else {
            keys = li.keys;
            tars = li.tars;
        }
        Map<Integer, List<Integer>> keysByEntry = new LinkedHashMap<>();
        List<Integer> entries = new ArrayList<>();
        for (int k = 0; k < keys.length; k++) {
            int e = g.blockOfPc[tars[k + 1]];
            if (e < 0) continue;
            if (!keysByEntry.containsKey(e)) entries.add(e);
            keysByEntry.computeIfAbsent(e, x -> new ArrayList<>()).add(keys[k]);
        }
        int join = -1;
        for (int e : entries) join = join == -1 ? e : g.join(join, e);
        if (dflt >= 0) join = join == -1 ? dflt : g.join(join, dflt);
        if (join >= g.n) join = -1; // no real rendezvous (cases re-dispatch into a loop)

        Set<Integer> bx = new HashSet<>(exits);
        if (join >= 0) bx.add(join);
        Set<Integer> caseLoop = loop == null ? null : new HashSet<>(loop);
        if (caseLoop != null) {
            caseLoop.addAll(entries);
            if (dflt >= 0) caseLoop.add(dflt);
        }
        List<Case> cases = new ArrayList<>();
        for (int e : entries) {
            Set<Integer> caseExits = new HashSet<>(bx);
            for (int o : entries) if (o != e) caseExits.add(o);
            if (dflt >= 0) caseExits.add(dflt);
            caseExits.add(head); // a case jumping back to this switch re-dispatches (loop back-edge)
            caseExits.remove(e); // the case's own entry must not stop its walk
            R r = region(e, caseLoop, caseExits, active);
            int[] ks = keysByEntry.get(e).stream().mapToInt(Integer::intValue).toArray();
            cases.add(new Case(ks, false, e, r.last, r.stmts));
        }
        if (dflt >= 0 && !keysByEntry.containsKey(dflt)) {
            Set<Integer> caseExits = new HashSet<>(bx);
            caseExits.addAll(entries);
            caseExits.add(head);
            caseExits.remove(dflt);
            R r = region(dflt, caseLoop, caseExits, active);
            cases.add(new Case(new int[0], true, dflt, r.last, r.stmts));
        }
        return new Sw(head, cases, join);
    }
}