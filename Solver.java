import java.util.*;

/**
 * Bidirectional BFS solver for the Rubik's Cube.
 *
 * Searches outward from BOTH the scrambled state and the solved state,
 * stopping when the two frontiers overlap. This "meet-in-the-middle"
 * strategy keeps the maximum frontier depth at ≈ n/2 for an n-move
 * scramble, which makes the search extremely fast (sub-second for ≤ 10
 * move scrambles on typical hardware).
 *
 * The solution returned is always optimal (fewest moves).
 */
public class Solver {

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Encode a 54-byte state as a compact String key for HashMap lookup. */
    static String key(byte[] s) {
        char[] c = new char[54];
        for (int i = 0; i < 54; i++) c[i] = (char) ('0' + s[i]);
        return new String(c);
    }

    /** Decode a key back into a byte[] state. */
    static byte[] decode(String k) {
        byte[] s = new byte[54];
        for (int i = 0; i < 54; i++) s[i] = (byte) (k.charAt(i) - '0');
        return s;
    }

    /** Return the inverse of a move (CW↔CCW, double stays double). */
    public static Cube.Move inverseOf(Cube.Move m) {
        int base = (m.ordinal() / 3) * 3;
        int mod  = m.ordinal() % 3;
        return Cube.Move.values()[base + (mod == 0 ? 2 : mod == 2 ? 0 : 1)];
    }

    /**
     * Combine a forward path (scrambled→intersection) and a backward path
     * (solved→intersection) into a complete solution (scrambled→solved).
     *
     * The backward path must be reversed and each move inverted.
     */
    private static List<Cube.Move> combine(List<Cube.Move> fwd, List<Cube.Move> bwd) {
        List<Cube.Move> result = new ArrayList<>(fwd);
        for (int i = bwd.size() - 1; i >= 0; i--) result.add(inverseOf(bwd.get(i)));
        return result;
    }

    // ---------------------------------------------------------------
    // Solver entry point
    // ---------------------------------------------------------------

    /**
     * Solve the given cube and return the optimal move sequence.
     * Returns an empty list if the cube is already solved,
     * or {@code null} if no solution was found within the depth limit.
     *
     * @param maxPerSide Maximum BFS depth from each side (default 5).
     *                   5 handles scrambles up to ~10 moves optimally.
     */
    public static List<Cube.Move> solve(Cube cube, int maxPerSide) {
        if (cube.isSolved()) return Collections.emptyList();

        String startKey = key(cube.state);
        String goalKey  = key(new Cube().state);   // solved state

        if (startKey.equals(goalKey)) return Collections.emptyList();

        // fwd[k] = shortest path from scrambled → state k
        // bwd[k] = shortest path from solved    → state k  (reversed+inverted when combining)
        Map<String, List<Cube.Move>> fwd = new HashMap<>();
        Map<String, List<Cube.Move>> bwd = new HashMap<>();
        fwd.put(startKey, Collections.emptyList());
        bwd.put(goalKey,  Collections.emptyList());

        // Quick check: cube is actually solved already
        if (fwd.containsKey(goalKey))  return combine(fwd.get(goalKey), Collections.emptyList());
        if (bwd.containsKey(startKey)) return combine(Collections.emptyList(), bwd.get(startKey));

        List<String> fwdFrontier = new ArrayList<>(List.of(startKey));
        List<String> bwdFrontier = new ArrayList<>(List.of(goalKey));

        Cube.Move[] allMoves = Cube.Move.values();

        for (int depth = 0; depth < maxPerSide; depth++) {

            // ---- expand forward frontier one level ----
            List<String> newFwd = new ArrayList<>();
            for (String k : fwdFrontier) {
                List<Cube.Move> path = fwd.get(k);
                byte[] st = decode(k);
                for (Cube.Move m : allMoves) {
                    byte[] nst = Cube.applyMoveToState(st, m);
                    String nk  = key(nst);
                    if (!fwd.containsKey(nk)) {
                        List<Cube.Move> np = new ArrayList<>(path); np.add(m);
                        fwd.put(nk, np);
                        newFwd.add(nk);
                        if (bwd.containsKey(nk)) return combine(np, bwd.get(nk));
                    }
                }
            }
            fwdFrontier = newFwd;
            if (fwdFrontier.isEmpty()) break;

            // ---- expand backward frontier one level ----
            List<String> newBwd = new ArrayList<>();
            for (String k : bwdFrontier) {
                List<Cube.Move> path = bwd.get(k);
                byte[] st = decode(k);
                for (Cube.Move m : allMoves) {
                    byte[] nst = Cube.applyMoveToState(st, m);
                    String nk  = key(nst);
                    if (!bwd.containsKey(nk)) {
                        List<Cube.Move> np = new ArrayList<>(path); np.add(m);
                        bwd.put(nk, np);
                        newBwd.add(nk);
                        if (fwd.containsKey(nk)) return combine(fwd.get(nk), np);
                    }
                }
            }
            bwdFrontier = newBwd;
            if (bwdFrontier.isEmpty()) break;
        }

        return null; // not found within depth limit
    }

    /** Solve with the default depth limit of 5 per side (handles ≤ 10-move scrambles). */
    public static List<Cube.Move> solve(Cube cube) {
        return solve(cube, 5);
    }
}
