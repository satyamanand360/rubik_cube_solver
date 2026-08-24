import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Computes, saves, and loads a Corner Pattern Database (PDB) for Korf's IDA* solver.
 * The PDB stores the exact minimum number of moves to solve the corners of the Rubik's cube,
 * acting as a perfect admissible heuristic.
 * 
 * 8 corners. 8! permutations. 3^7 orientations. Total states = 88,179,840.
 * Memory footprint: ~88 MB.
 */
public class PatternDatabase {

    private static final String PDB_FILE = "corner_pdb.dat";
    private static final int NUM_CORNERS = 8;
    private static final int NUM_STATES = 88179840; // 8! * 3^7

    // Facelet indices for the 8 corners. [corner][0=Primary(U/D), 1=CW, 2=CCW]
    private static final int[][] CORNER_FACELETS = {
        {8,  9, 20}, // URF: U8, R0, F2
        {6, 18, 38}, // UFL: U6, F0, L2
        {0, 36, 47}, // ULB: U0, L0, B2
        {2, 45, 11}, // UBR: U2, B0, R2
        {29, 26, 15}, // DFR: D2, F8, R6
        {27, 44, 24}, // DLF: D0, L8, F6
        {33, 53, 42}, // DBL: D6, B8, L6
        {35, 17, 51}  // DRB: D8, R8, B6
    };

    // The solved face colors for each corner's facelets.
    // Derived directly from the home face of each facelet (index / 9).
    private static final int[][] SOLVED_COLORS = new int[8][3];
    static {
        for (int c = 0; c < 8; c++) {
            for (int i = 0; i < 3; i++) {
                SOLVED_COLORS[c][i] = CORNER_FACELETS[c][i] / 9;
            }
        }
    }

    private byte[] pdb;
    private boolean ready = false;

    public PatternDatabase() {
        pdb = new byte[NUM_STATES];
    }

    public boolean isReady() {
        return ready;
    }

    /** Returns the heuristic value (minimum moves to solve corners) for the given cube state. */
    public int getHeuristic(byte[] state) {
        return pdb[getIndex(state)];
    }

    /** 
     * Loads the PDB from disk if it exists, otherwise builds it and saves it.
     */
    public void loadOrBuild() {
        File file = new File(PDB_FILE);
        if (file.exists() && file.length() == NUM_STATES) {
            System.out.println("Loading Corner Pattern Database from disk...");
            try {
                pdb = Files.readAllBytes(file.toPath());
                System.out.println("Loaded.");
                ready = true;
                return;
            } catch (IOException e) {
                System.out.println("Failed to read PDB, rebuilding...");
            }
        }
        build();
        try {
            System.out.println("Saving PDB to disk...");
            Files.write(file.toPath(), pdb);
        } catch (IOException e) {
            System.out.println("Failed to save PDB to disk: " + e.getMessage());
        }
        ready = true;
    }

    /** Computes the index (0 to 88,179,839) for the corner state of the given cube facelets. */
    public static int getIndex(byte[] state) {
        int[] perm = new int[8];
        int[] ori = new int[8];

        for (int i = 0; i < 8; i++) {
            int cPrimary = state[CORNER_FACELETS[i][0]];
            int cCW      = state[CORNER_FACELETS[i][1]];
            int cCCW     = state[CORNER_FACELETS[i][2]];

            // Find which physical corner this is, and its orientation
            for (int j = 0; j < 8; j++) {
                if (cPrimary == SOLVED_COLORS[j][0] && cCW == SOLVED_COLORS[j][1] && cCCW == SOLVED_COLORS[j][2]) {
                    perm[i] = j; ori[i] = 0; break;
                }
                if (cPrimary == SOLVED_COLORS[j][1] && cCW == SOLVED_COLORS[j][2] && cCCW == SOLVED_COLORS[j][0]) {
                    perm[i] = j; ori[i] = 1; break;
                }
                if (cPrimary == SOLVED_COLORS[j][2] && cCW == SOLVED_COLORS[j][0] && cCCW == SOLVED_COLORS[j][1]) {
                    perm[i] = j; ori[i] = 2; break;
                }
            }
        }

        // Lehmer code for permutation (factoradic)
        int permIdx = 0;
        for (int i = 0; i < 7; i++) {
            int count = 0;
            for (int j = i + 1; j < 8; j++) {
                if (perm[j] < perm[i]) count++;
            }
            permIdx = (permIdx + count) * (8 - 1 - i);
        }
        // Base-3 for orientation (only 7 needed, 8th is deterministic)
        int oriIdx = 0;
        for (int i = 0; i < 7; i++) {
            oriIdx = oriIdx * 3 + ori[i];
        }

        return permIdx * 2187 + oriIdx;
    }

    private void build() {
        System.out.println("Building Corner Pattern Database... (this will take ~10-30s)");
        Arrays.fill(pdb, (byte) -1);

        Cube root = new Cube();
        int rootIdx = getIndex(root.state);
        pdb[rootIdx] = 0;

        // Queue for BFS. 88M * 8 bytes = ~705 MB RAM.
        long[] queue = new long[NUM_STATES];
        int head = 0, tail = 0;
        queue[tail++] = encode(root.state);

        int visited = 1;
        byte[] tempState = new byte[Cube.NUM_FACELETS];
        Cube.Move[] moves = Cube.Move.values();

        while (head < tail) {
            long currentEncoded = queue[head++];
            decode(currentEncoded, tempState);
            int currentIdx = getIndex(tempState);
            byte currentDepth = pdb[currentIdx];

            for (Cube.Move m : moves) {
                byte[] nextState = Cube.applyMoveToState(tempState, m);
                int nextIdx = getIndex(nextState);
                if (pdb[nextIdx] == -1) {
                    pdb[nextIdx] = (byte) (currentDepth + 1);
                    queue[tail++] = encode(nextState);
                    visited++;
                    if (visited % 1000000 == 0) {
                        System.out.println("PDB Build: " + visited + " / " + NUM_STATES + " states");
                    }
                }
            }
        }
        System.out.println("PDB Build complete.");
    }

    /** Encodes the 8 corner permutation and orientations into a 40-bit long. */
    private static long encode(byte[] state) {
        long encoded = 0;
        for (int i = 0; i < 8; i++) {
            int cPrimary = state[CORNER_FACELETS[i][0]];
            int cCW      = state[CORNER_FACELETS[i][1]];
            int cCCW     = state[CORNER_FACELETS[i][2]];

            int p = 0, o = 0;
            for (int j = 0; j < 8; j++) {
                if (cPrimary == SOLVED_COLORS[j][0] && cCW == SOLVED_COLORS[j][1] && cCCW == SOLVED_COLORS[j][2]) {
                    p = j; o = 0; break;
                }
                if (cPrimary == SOLVED_COLORS[j][1] && cCW == SOLVED_COLORS[j][2] && cCCW == SOLVED_COLORS[j][0]) {
                    p = j; o = 1; break;
                }
                if (cPrimary == SOLVED_COLORS[j][2] && cCW == SOLVED_COLORS[j][0] && cCCW == SOLVED_COLORS[j][1]) {
                    p = j; o = 2; break;
                }
            }
            encoded |= ((long) p << (i * 5));
            encoded |= ((long) o << (i * 5 + 3));
        }
        return encoded;
    }

    /** Decodes a 40-bit long back into the 24 corner facelets of a byte[] state. */
    private static void decode(long encoded, byte[] state) {
        for (int i = 0; i < 8; i++) {
            int p = (int) ((encoded >> (i * 5)) & 7);
            int o = (int) ((encoded >> (i * 5 + 3)) & 3);

            if (o == 0) {
                state[CORNER_FACELETS[i][0]] = (byte) SOLVED_COLORS[p][0];
                state[CORNER_FACELETS[i][1]] = (byte) SOLVED_COLORS[p][1];
                state[CORNER_FACELETS[i][2]] = (byte) SOLVED_COLORS[p][2];
            } else if (o == 1) {
                state[CORNER_FACELETS[i][0]] = (byte) SOLVED_COLORS[p][1];
                state[CORNER_FACELETS[i][1]] = (byte) SOLVED_COLORS[p][2];
                state[CORNER_FACELETS[i][2]] = (byte) SOLVED_COLORS[p][0];
            } else {
                state[CORNER_FACELETS[i][0]] = (byte) SOLVED_COLORS[p][2];
                state[CORNER_FACELETS[i][1]] = (byte) SOLVED_COLORS[p][0];
                state[CORNER_FACELETS[i][2]] = (byte) SOLVED_COLORS[p][1];
            }
        }
    }
}
