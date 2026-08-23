import java.util.*;

public class TestCube {
    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) { pass++; }
        else { fail++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        // 1. Each base move applied 4 times = identity (quarter turn ^4 = id)
        for (Cube.Move base : new Cube.Move[]{Cube.Move.U1, Cube.Move.D1, Cube.Move.F1,
                Cube.Move.B1, Cube.Move.R1, Cube.Move.L1}) {
            Cube c = new Cube();
            for (int i = 0; i < 4; i++) c.applyMove(base);
            check(base + "^4 = identity", c.isSolved());
        }

        // 2. Move then its inverse (CCW) = identity
        Cube.Move[][] pairs = {
            {Cube.Move.U1, Cube.Move.U3}, {Cube.Move.D1, Cube.Move.D3},
            {Cube.Move.F1, Cube.Move.F3}, {Cube.Move.B1, Cube.Move.B3},
            {Cube.Move.R1, Cube.Move.R3}, {Cube.Move.L1, Cube.Move.L3}
        };
        for (Cube.Move[] p : pairs) {
            Cube c = new Cube();
            c.applyMove(p[0]); c.applyMove(p[1]);
            check(p[0] + " then " + p[1] + " = identity", c.isSolved());
        }

        // 3. Double move = quarter move applied twice
        Cube.Move[][] dbls = {
            {Cube.Move.U1, Cube.Move.U2}, {Cube.Move.D1, Cube.Move.D2},
            {Cube.Move.F1, Cube.Move.F2}, {Cube.Move.B1, Cube.Move.B2},
            {Cube.Move.R1, Cube.Move.R2}, {Cube.Move.L1, Cube.Move.L2}
        };
        for (Cube.Move[] p : dbls) {
            Cube a = new Cube(); a.applyMove(p[0]); a.applyMove(p[0]);
            Cube b = new Cube(); b.applyMove(p[1]);
            check(p[1] + " == " + p[0] + " twice", Arrays.equals(a.state, b.state));
        }

        // 4. Opposite faces commute: U D == D U, R L == L R, F B == F B
        check("U,D commute", commute(Cube.Move.U1, Cube.Move.D1));
        check("R,L commute", commute(Cube.Move.R1, Cube.Move.L1));
        check("F,B commute", commute(Cube.Move.F1, Cube.Move.B1));

        // 5. Adjacent faces do NOT commute (sanity: U R != R U)
        check("U,R do NOT commute (sanity)", !commute(Cube.Move.U1, Cube.Move.R1));

        // 6. Famous identity: (R U R' U')^6 = identity
        Cube c6 = new Cube();
        for (int i = 0; i < 6; i++) {
            c6.applyMove(Cube.Move.R1);
            c6.applyMove(Cube.Move.U1);
            c6.applyMove(Cube.Move.R3);
            c6.applyMove(Cube.Move.U3);
        }
        check("(R U R' U')^6 = identity", c6.isSolved());

        // 7. Famous identity: (R U2 R' U' R U' R')*3 = identity is another well known
        //    one (order 3) -- sexy-move variant. Use simpler: full cube rotation
        //    equivalent check -- (U)*4 already covered. Add: R U R' U' R U R' U' R U R' U' 
        //    == already covered by #6 with 3 reps not needed.

        // 8. Any single quarter-turn changes the state (not accidentally identity)
        for (Cube.Move m : Cube.Move.values()) {
            if (m.toString().endsWith("2")) continue; // check quarter turns only here implicitly
            Cube c = new Cube();
            c.applyMove(m);
            check(m + " changes solved state", !c.isSolved());
        }

        // 9. Visual sanity print after a scramble + solve-back
        Cube demo = new Cube();
        List<Cube.Move> scramble = Arrays.asList(Cube.Move.R1, Cube.Move.U1, Cube.Move.F3, Cube.Move.D2, Cube.Move.L1);
        demo.applyMoves(scramble);
        System.out.println("\nAfter scramble R U F' D2 L:");
        demo.print();
        List<Cube.Move> inverse = Arrays.asList(Cube.Move.L3, Cube.Move.D2, Cube.Move.F1, Cube.Move.U3, Cube.Move.R3);
        demo.applyMoves(inverse);
        check("scramble then exact inverse = identity", demo.isSolved());

        System.out.println("\n" + pass + " passed, " + fail + " failed.");
        if (fail > 0) System.exit(1);
    }

    static boolean commute(Cube.Move a, Cube.Move b) {
        Cube c1 = new Cube(); c1.applyMove(a); c1.applyMove(b);
        Cube c2 = new Cube(); c2.applyMove(b); c2.applyMove(a);
        return Arrays.equals(c1.state, c2.state);
    }
}
