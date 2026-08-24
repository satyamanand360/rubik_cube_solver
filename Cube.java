import java.util.*;

/**
 * Represents a 3x3 Rubik's Cube using the classic "facelet" model:
 * 54 stickers (6 faces x 9), stored as a flat byte array.
 *
 * Face order / index ranges:
 *   U = 0..8   R = 9..17   F = 18..26
 *   D = 27..35 L = 36..44  B = 45..53
 * Each face's 9 stickers are numbered 0..8 in row-major order
 * (top-left to bottom-right, as seen looking directly at that face).
 *
 * Move permutation tables (which facelet index moves to which index for
 * each of the 6 quarter turns) are NOT hand-typed magic numbers -- they
 * are derived at class-load time from the actual 3D geometry of the cube
 * (each facelet's 3D position + outward normal, rotated by the correct
 * 90-degree rotation matrix for that face). This eliminates an entire
 * class of "I mis-copied an index" bugs that plague hand-built cube
 * solvers, and it's easy to extend/verify since it's just linear algebra.
 */
public class Cube {

    public static final int NUM_FACELETS = 54;

    // Face indices
    public static final int U = 0, R = 1, F = 2, D = 3, L = 4, B = 5;
    public static final int[] FACE_START = {0, 9, 18, 27, 36, 45};
    public static final char[] FACE_CHAR = {'U', 'R', 'F', 'D', 'L', 'B'};

    /** The 18 standard moves. */
    public enum Move {
        U1, U2, U3, D1, D2, D3, F1, F2, F3,
        R1, R2, R3, L1, L2, L3, B1, B2, B3;

        public String toString() {
            char face = "UDFRLB".charAt(ordinal() / 3);
            int mag = ordinal() % 3; // 0=CW(1), 1=double(2), 2=CCW(')
            return switch (mag) {
                case 0 -> String.valueOf(face);
                case 1 -> face + "2";
                default -> face + "'";
            };
        }
    }

    // state[i] = the FACE identity (0..5) currently showing at facelet slot i.
    // A solved cube has state[i] = i / 9 (each facelet shows its home face's color).
    public byte[] state;

    public Cube() {
        state = new byte[NUM_FACELETS];
        reset();
    }

    public void reset() {
        for (int i = 0; i < NUM_FACELETS; i++) state[i] = (byte) (i / 9);
    }

    public boolean isSolved() {
        for (int i = 0; i < NUM_FACELETS; i++) if (state[i] != i / 9) return false;
        return true;
    }

    public Cube copy() {
        Cube c = new Cube();
        c.state = state.clone();
        return c;
    }

    // ---------------------------------------------------------------
    // Move tables: permTable[faceIdx (U,D,F,B,R,L order 0..5)] = int[54]
    // permTable[m][newIndex] = oldIndex   (pull-based permutation)
    // These are the CLOCKWISE quarter turns for U,D,F,B,R,L respectively,
    // built once, geometrically, in buildBaseMoveTables().
    // ---------------------------------------------------------------
    private static final int[][] CW_PERM = buildBaseMoveTables();

    // Full 18-move table, derived from the 6 base clockwise perms.
    private static final int[][] MOVE_PERM = buildFullMoveTables(CW_PERM);

    public void applyMove(Move m) {
        state = applyMoveToState(state, m);
    }

    public void applyMoves(List<Move> moves) {
        for (Move m : moves) applyMove(m);
    }

    // ---------------------------------------------------------------
    // Geometric derivation of the base clockwise move permutations.
    // ---------------------------------------------------------------

    // 3D position (x,y,z) and outward unit normal for facelet (face,row,col),
    // following a single consistent convention derived from the standard
    // U-R-F-D-L-B net layout (U above F, D below F, L-F-R-B left to right).
    private static int[] facePos(int face, int row, int col) {
        // returns {x, y, z}
        return switch (face) {
            case U -> new int[]{col - 1, 1, row - 1};
            case D -> new int[]{col - 1, -1, 1 - row};
            case F -> new int[]{col - 1, 1 - row, 1};
            case B -> new int[]{1 - col, 1 - row, -1};
            case R -> new int[]{1, 1 - row, 1 - col};
            case L -> new int[]{-1, 1 - row, col - 1};
            default -> throw new IllegalArgumentException();
        };
    }

    private static int[] faceNormal(int face) {
        return switch (face) {
            case U -> new int[]{0, 1, 0};
            case D -> new int[]{0, -1, 0};
            case F -> new int[]{0, 0, 1};
            case B -> new int[]{0, 0, -1};
            case R -> new int[]{1, 0, 0};
            case L -> new int[]{-1, 0, 0};
            default -> throw new IllegalArgumentException();
        };
    }

    // Inverse of facePos: given a face and (x,y,z) known to lie on it, find (row,col).
    private static int[] rowColOf(int face, int x, int y, int z) {
        return switch (face) {
            case U -> new int[]{z + 1, x + 1};
            case D -> new int[]{1 - z, x + 1};
            case F -> new int[]{1 - y, x + 1};
            case B -> new int[]{1 - y, 1 - x};
            case R -> new int[]{1 - y, 1 - z};
            case L -> new int[]{1 - y, z + 1};
            default -> throw new IllegalArgumentException();
        };
    }

    // Applies the clockwise-quarter-turn rotation for `moveFace` to a vector (x,y,z).
    // Derived from "clockwise viewed from outside the face" = negative rotation
    // about the face's outward normal axis.
    private static int[] rotate(int moveFace, int x, int y, int z) {
        return switch (moveFace) {
            case U -> new int[]{-z, y, x};   // (x,z) -> (-z,x), y fixed
            case D -> new int[]{z, y, -x};   // (x,z) -> (z,-x), y fixed
            case F -> new int[]{y, -x, z};   // (x,y) -> (y,-x), z fixed
            case B -> new int[]{-y, x, z};   // (x,y) -> (-y,x), z fixed
            case R -> new int[]{x, z, -y};   // (y,z) -> (z,-y), x fixed
            case L -> new int[]{x, -z, y};   // (y,z) -> (-z,y), x fixed
            default -> throw new IllegalArgumentException();
        };
    }

    private static boolean affectedByMove(int moveFace, int x, int y, int z) {
        return switch (moveFace) {
            case U -> y == 1;
            case D -> y == -1;
            case F -> z == 1;
            case B -> z == -1;
            case R -> x == 1;
            case L -> x == -1;
            default -> throw new IllegalArgumentException();
        };
    }

    private static int faceOfNormal(int nx, int ny, int nz) {
        for (int f = 0; f < 6; f++) {
            int[] n = faceNormal(f);
            if (n[0] == nx && n[1] == ny && n[2] == nz) return f;
        }
        throw new IllegalStateException("bad normal " + nx + "," + ny + "," + nz);
    }

    private static int index(int face, int row, int col) {
        return FACE_START[face] + row * 3 + col;
    }

    /** Builds the 6 base clockwise-quarter-turn permutations (U,D,F,B,R,L order). */
    private static int[][] buildBaseMoveTables() {
        int[][] tables = new int[6][NUM_FACELETS];
        int[] moveFaces = {U, D, F, B, R, L};

        for (int mi = 0; mi < 6; mi++) {
            int moveFace = moveFaces[mi];
            int[] perm = new int[NUM_FACELETS]; // perm[newIndex] = oldIndex
            Arrays.fill(perm, -1);

            for (int face = 0; face < 6; face++) {
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 3; col++) {
                        int oldIdx = index(face, row, col);
                        int[] pos = facePos(face, row, col);
                        int[] n = faceNormal(face);

                        if (!affectedByMove(moveFace, pos[0], pos[1], pos[2])) {
                            perm[oldIdx] = oldIdx; // unaffected: identity
                            continue;
                        }

                        int[] newPos = rotate(moveFace, pos[0], pos[1], pos[2]);
                        int[] newN = rotate(moveFace, n[0], n[1], n[2]);
                        int newFace = faceOfNormal(newN[0], newN[1], newN[2]);
                        int[] rc = rowColOf(newFace, newPos[0], newPos[1], newPos[2]);
                        int newIdx = index(newFace, rc[0], rc[1]);

                        perm[newIdx] = oldIdx;
                    }
                }
            }
            for (int i = 0; i < NUM_FACELETS; i++) {
                if (perm[i] == -1) throw new IllegalStateException("Unfilled slot " + i);
            }
            tables[mi] = perm;
        }
        return tables;
    }

    /** Expands the 6 clockwise base tables into all 18 moves (CW, double, CCW). */
    private static int[][] buildFullMoveTables(int[][] cwPerm) {
        int[][] full = new int[18][];
        // Move enum order is U1,U2,U3,D1,D2,D3,F1,F2,F3,R1,R2,R3,L1,L2,L3,B1,B2,B3
        // cwPerm index order is U,D,F,B,R,L (0..5) -- map enum group -> cwPerm index
        int[] group2cw = {0, 1, 2, 4, 5, 3}; // U->0,D->1,F->2,R->4,L->5,B->3
        for (int g = 0; g < 6; g++) {
            int[] base = cwPerm[group2cw[g]];
            int[] once = base;
            int[] twice = applyAfter(base, base);     // base, then base again
            int[] thrice = applyAfter(twice, base);   // twice, then base again
            full[g * 3] = once;
            full[g * 3 + 1] = twice;
            full[g * 3 + 2] = thrice;
        }
        return full;
    }

    // Pull-permutations compose "backwards": if `first` is applied to the solved
    // state, then `second` is applied on top, the combined pull-perm is
    // combined[i] = first[ second[i] ].
    private static int[] applyAfter(int[] first, int[] second) {
        int[] result = new int[NUM_FACELETS];
        for (int i = 0; i < NUM_FACELETS; i++) result[i] = first[second[i]];
        return result;
    }

    // ---------------------------------------------------------------
    // Static helpers used by Solver and CubeServer
    // ---------------------------------------------------------------

    /** Apply a move directly to a raw state array and return the result. */
    public static byte[] applyMoveToState(byte[] state, Move m) {
        int[] perm = MOVE_PERM[m.ordinal()];
        byte[] next = new byte[NUM_FACELETS];
        for (int i = 0; i < NUM_FACELETS; i++) next[i] = state[perm[i]];
        return next;
    }

    /** Expose the full 18-move permutation table (used for JSON serialisation). */
    public static int[][] getMovePerm() { return MOVE_PERM; }

    // ---------------------------------------------------------------
    // Debug printing
    // ---------------------------------------------------------------
    public void print() {
        String[] names = {"U", "R", "F", "D", "L", "B"};
        for (int f = 0; f < 6; f++) {
            System.out.print(names[f] + ": ");
            for (int i = 0; i < 9; i++) {
                System.out.print(FACE_CHAR[state[FACE_START[f] + i]]);
            }
            System.out.println();
        }
    }
}
