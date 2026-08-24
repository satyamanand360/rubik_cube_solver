import java.util.*;

/**
 * Solves the Rubik's Cube using Korf's Iterative Deepening A* (IDA*) algorithm.
 * Uses the Corner Pattern Database as an admissible heuristic.
 */
public class IDAStarSolver {

    private PatternDatabase pdb;

    public IDAStarSolver(PatternDatabase pdb) {
        this.pdb = pdb;
    }

    public List<Cube.Move> solve(Cube cube) {
        if (cube.isSolved()) return Collections.emptyList();

        int threshold = pdb.getHeuristic(cube.state);
        List<Cube.Move> path = new ArrayList<>();

        while (true) {
            System.out.println("IDA* Depth: " + threshold);
            int nextThreshold = search(cube.state, 0, threshold, -1, path);
            if (nextThreshold == -1) {
                return path; // Found!
            }
            if (nextThreshold == Integer.MAX_VALUE) {
                return null; // Unsolvable or disconnected state
            }
            threshold = nextThreshold;
        }
    }

    /**
     * @return -1 if found, else the minimum threshold to try next.
     */
    private int search(byte[] state, int g, int threshold, int prevFace, List<Cube.Move> path) {
        int h = pdb.getHeuristic(state);
        int f = g + h;

        if (f > threshold) return f;
        if (h == 0 && isSolved(state)) return -1; // Found!

        int min = Integer.MAX_VALUE;
        Cube.Move[] moves = Cube.Move.values();

        for (Cube.Move m : moves) {
            int currFace = m.ordinal() / 3;
            if (prevFace != -1) {
                if (currFace == prevFace) continue;
                // Enforce order on opposite faces to avoid redundant paths (e.g. U D vs D U)
                // Opposites are 0-1, 2-5, 3-4.
                // Wait: U(0), D(1), F(2), R(3), L(4), B(5).
                // Opposites: 0&1, 2&5, 3&4. 
                if ((prevFace == 0 && currFace == 1) || 
                    (prevFace == 2 && currFace == 5) || 
                    (prevFace == 3 && currFace == 4)) {
                    continue;
                }
            }

            byte[] nextState = Cube.applyMoveToState(state, m);
            path.add(m);

            int t = search(nextState, g + 1, threshold, currFace, path);
            if (t == -1) return -1; // Found
            if (t < min) min = t;

            path.remove(path.size() - 1);
        }
        return min;
    }

    private boolean isSolved(byte[] state) {
        for (int i = 0; i < Cube.NUM_FACELETS; i++) {
            if (state[i] != i / 9) return false;
        }
        return true;
    }
}
