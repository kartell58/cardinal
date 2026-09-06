package io.github.kartell58.cardinal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Control-flow graph over basic blocks, with dominators, post-dominators and back edges.
 * Blocks are renumbered in reverse post-order (RPO) so ids are execution-friendly.
 */
final class Cfg {

    record Bb(int id, int start, int end) {}

    final List<Insn> all;
    final List<Bb> bbs;
    final int n;
    final int[][] succ, pred;
    final int[] idom, ipd, pdDepth;
    final int[] blockOfInsn; // insn index -> block id
    final int[] blockOfPc;   // insn pc -> block id
    final List<int[]> backEdges;

    private Cfg(List<Insn> all) {
        this.all = all;
        int len = all.size();
        Map<Integer, Integer> pcIdx = new HashMap<>();
        for (int i = 0; i < len; i++) pcIdx.put(all.get(i).pc, i);

        boolean[] leader = new boolean[len];
        leader[0] = true;
        for (int i = 0; i < len; i++) {
            Insn x = all.get(i);
            if (x.tars != null) {
                for (int t : x.tars) {
                    Integer ti = pcIdx.get(t);
                    if (ti != null) leader[ti] = true;
                }
                if (fallsThrough(x) && i + 1 < len) leader[i + 1] = true;
            } else if (x.op == Insn.RET || x.op == Insn.JSR) {
                if (i + 1 < len) leader[i + 1] = true;
            }
        }

        List<Bb> raw = new ArrayList<>();
        int i = 0;
        while (i < len) {
            int start = i;
            while (i + 1 < len && !leader[i + 1]) i++;
            raw.add(new Bb(raw.size(), start, i));
            i++;
        }
        int r = raw.size();

        int[] bInsn = new int[len];
        Arrays.fill(bInsn, -1);
        for (Bb b : raw) for (int k = b.start; k <= b.end; k++) bInsn[k] = b.id;

        int[][] rawSucc = new int[r][];
        for (int b = 0; b < r; b++) {
            List<Integer> s = new ArrayList<>();
            Insn last = all.get(raw.get(b).end);
            if (last.tars != null) {
                for (int t : last.tars) {
                    Integer ti = pcIdx.get(t);
                    if (ti != null) {
                        int bl = bInsn[ti];
                        if (bl != -1) s.add(bl);
                    }
                }
                if (fallsThrough(last) && raw.get(b).end + 1 < len) {
                    int bl = bInsn[raw.get(b).end + 1];
                    if (bl != -1) s.add(bl);
                }
            } else if (raw.get(b).end + 1 < len && fallsThrough(last)) {
                int bl = bInsn[raw.get(b).end + 1];
                if (bl != -1) s.add(bl);
            }
            rawSucc[b] = s.stream().mapToInt(Integer::intValue).toArray();
        }

        int[] order = rpo(rawSucc, 0);
        Map<Integer, Integer> remap = new HashMap<>();
        for (int k = 0; k < order.length; k++) remap.put(order[k], k);

        n = order.length;
        bbs = new ArrayList<>(n);
        for (int id : order) bbs.add(raw.get(id));

        succ = new int[n][];
        for (int k = 0; k < n; k++) {
            List<Integer> s = new ArrayList<>();
            for (int t : rawSucc[order[k]]) {
                Integer m = remap.get(t);
                if (m != null) s.add(m);
            }
            succ[k] = s.stream().mapToInt(Integer::intValue).toArray();
        }
        pred = new int[n][];
        for (int k = 0; k < n; k++) {
            List<Integer> p = new ArrayList<>();
            for (int j = 0; j < n; j++) for (int t : succ[j]) if (t == k) p.add(j);
            pred[k] = p.stream().mapToInt(Integer::intValue).toArray();
        }

        for (int k = 0; k < len; k++) {
            Integer m = remap.get(bInsn[k]);
            bInsn[k] = m == null ? -1 : m;
        }
        blockOfInsn = bInsn;

        blockOfPc = new int[all.get(len - 1).pc + 1];
        Arrays.fill(blockOfPc, -1);
        for (int k = 0; k < len; k++) blockOfPc[all.get(k).pc] = bInsn[k];

        idom = idom(n, 0, pred);
        int[][] pdm = postDom();
        ipd = pdm[0];
        pdDepth = pdm[1];

        List<int[]> be = new ArrayList<>();
        for (int a = 0; a < n; a++) for (int b : succ[a]) if (dom(b, a)) be.add(new int[]{a, b});
        backEdges = be;
    }

    static Cfg build(List<Insn> all) {
        return all.isEmpty() ? null : new Cfg(all);
    }

    String dbg() {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) {
            Bb b = bbs.get(k);
            Insn f = all.get(b.start()), l = all.get(b.end());
            sb.append(k).append(": pc").append(f.pc).append("..").append(l.pc)
              .append(" succ=").append(java.util.Arrays.toString(succ[k])).append("\n");
        }
        for (int[] e : backEdges) sb.append("backedge ").append(e[0]).append("->").append(e[1]).append("\n");
        if (ipd != null) {
            for (int k = 0; k < n; k++) sb.append("  pd[").append(k).append("]=").append(ipd[k])
                    .append(" d=").append(pdDepth[k]).append("\n");
        }
        for (Insn x : all)
            sb.append("  pc").append(x.pc).append(" op=").append(x.op)
              .append(x.tars == null ? "" : " tars=" + java.util.Arrays.toString(x.tars)).append("\n");
        return sb.toString();
    }

    private static boolean fallsThrough(Insn x) {
        return switch (x.op) {
            case Insn.GOTO, Insn.GOTO_W, Insn.TABLESWITCH, Insn.LOOKUPSWITCH, Insn.RET, Insn.JSR,
                    Insn.IRETURN, Insn.LRETURN, Insn.FRETURN, Insn.DRETURN, Insn.ARETURN, Insn.RETURN, Insn.ATHROW -> false;
            default -> true;
        };
    }

    private static int[] rpo(int[][] succ, int entry) {
        List<Integer> post = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        boolean[] seen = new boolean[succ.length];
        boolean[] done = new boolean[succ.length];
        stack.push(entry);
        seen[entry] = true;
        while (!stack.isEmpty()) {
            int v = stack.peek();
            if (!done[v]) {
                done[v] = true;
                for (int w : succ[v]) if (!seen[w]) { seen[w] = true; stack.push(w); }
            } else {
                post.add(v);
                stack.pop();
            }
        }
        Collections.reverse(post);
        return post.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Immediate dominators in a graph given its predecessor lists; root fixed. */
    private static int[] idom(int size, int root, int[][] pred) {
        BitSet[] d = new BitSet[size];
        for (int i = 0; i < size; i++) {
            d[i] = new BitSet(size);
            d[i].set(0, size);
        }
        d[root].clear();
        d[root].set(root);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < size; i++) {
                if (i == root) continue;
                BitSet nb = new BitSet(size);
                nb.set(i);
                boolean first = true;
                for (int p : pred[i]) {
                    if (p == i) continue;
                    if (first) { nb.or(d[p]); first = false; }
                    else nb.and(d[p]);
                }
                if (first) nb.clear(); // unreachable
                if (!nb.equals(d[i])) { d[i] = nb; changed = true; }
            }
        }
        int[] id = new int[size];
        Arrays.fill(id, -1);
        for (int i = 0; i < size; i++) {
            if (i == root) continue;
            for (int j = 0; j < size && id[i] == -1; j++) {
                if (j != i && d[i].get(j)) {
                    boolean maximal = true;
                    for (int k = 0; k < size; k++) {
                        if (k != i && k != j && d[i].get(k) && !d[j].get(k)) { maximal = false; break; }
                    }
                    if (maximal) id[i] = j;
                }
            }
        }
        return id;
    }

    private int[][] postDom() {
        int m = n + 2; // m-1 = virtual exit (distinct from block nodes 1..n)
        int exit = m - 1;
        int[][] rev = new int[m][];
        List<List<Integer>> rl = new ArrayList<>(m);
        for (int i = 0; i < m; i++) rl.add(new ArrayList<>());
        for (int u = 0; u < n; u++) {
            for (int v : succ[u]) rl.get(u + 1).add(v + 1);
            if (succ[u].length == 0) rl.get(u + 1).add(exit);
        }
        for (int i = 0; i < m; i++) rev[i] = rl.get(i).stream().mapToInt(Integer::intValue).toArray();
        int[] pd = idom(m, exit, rev);
        int[] res = new int[n];
        int[] depth = new int[n];
        for (int u = 0; u < n; u++) {
            int d = pd[u + 1];
            res[u] = d == exit || d < 0 ? -1 : d - 1;
            depth[u] = res[u] < 0 ? 0 : depth[res[u]] + 1;
        }
        return new int[][]{res, depth};
    }

    boolean dom(int a, int b) {
        for (int x = b; x != -1; x = idom[x]) if (x == a) return true;
        return false;
    }

    /** Lowest common ancestor of two blocks in the post-dominator tree (-1 if none). */
    int join(int a, int b) {
        int x = a, y = b;
        while (x != y) {
            if (x < 0 || y < 0) return -1;
            if (pdDepth[x] >= pdDepth[y]) x = ipd[x];
            else y = ipd[y];
        }
        return x;
    }

    /** True if the target of block b's final goto lands outside its own closing construct. */
    boolean isBackEdgeTo(int from, int to) {
        return backEdges.stream().anyMatch(e -> e[0] == from && e[1] == to);
    }
}