# Rubik's Cube 3D Solver

A full-stack, interactive 3D Rubik's Cube application featuring a **glossy PBR-rendered cube**, two powerful solving algorithms, manual move controls, and a beautiful dark-mode UI — all built with a lightweight Java backend and a Three.js frontend.

---

## ✨ Features

### 🧠 Dual Solving Algorithms (switchable via tabs)
- **Bidirectional BFS** — Meet-in-the-middle search. Finds an *optimal* solution in under a second for scrambles up to ~16 moves.
- **Korf's IDA\*** — Iterative Deepening A\* with a precomputed **Corner Pattern Database** (88 million states) as an admissible heuristic. Finds provably optimal solutions for any valid cube state.
  - The PDB is built once (~1–2 min) and **persisted to disk** (`corner_pdb.dat`) — subsequent server starts load it instantly.
  - The server starts immediately and serves the UI while the PDB builds in a background thread.

### 🎮 Manual Move Controls
- Full 18-button grid for all standard Rubik's Cube moves (`U`, `U2`, `U'`, `D`, `F`, `R`, `L`, `B`, etc.).
- Each button **animates the 3D cube** and **syncs the backend state** incrementally — so you can manually scramble, then solve.
- Move history is logged in real-time.

### 🌟 Glossy / Reflective 3D Cube
- Uses Three.js `MeshPhysicalMaterial` with `clearcoat` for a realistic plastic sheen.
- Dynamic studio environment map generated via `PMREMGenerator` for real-time reflections.

### 🎨 Premium UI
- Dark-mode glassmorphism design with smooth animations.
- Algorithm comparison: displays moves count and solve time for each solver.
- Real-time statistics: solve count, best time, average time, total moves.
- Toast notifications and status indicators.

### 🔧 Additional
- **Scramble** with a configurable move count (slider).
- **Custom Position** panel: enter any standard notation sequence (`R U R' F2 L D2 B'`) to set a specific state and solve from it.
- **Reset** button returns to solved state.
- Adjustable animation speed (Very Slow → Very Fast).

---

## 🛠 Technologies Used

| Layer | Technology |
|-------|------------|
| Backend | Java 21 (pure JDK, no external libraries) |
| HTTP Server | `com.sun.net.httpserver` — lightweight REST API |
| Algorithm 1 | Bidirectional BFS (custom) |
| Algorithm 2 | Korf's IDA\* with Corner Pattern Database |
| Frontend | HTML5, Vanilla JS (ES Modules), CSS3 |
| 3D Engine | Three.js (via CDN / import map) |
| Styling | Custom CSS — dark-mode glassmorphism |

---

## 🚀 Getting Started

### Prerequisites
- **JDK 21+** installed
- A modern web browser (Chrome / Edge / Firefox)

### Run Locally

1. **Clone the repository:**
   ```bash
   git clone https://github.com/satyamanand360/rubik_cube_solver.git
   cd rubik_cube_solver
   ```

2. **Compile all Java files:**
   ```bash
   javac -d . Cube.java Solver.java PatternDatabase.java IDAStarSolver.java CubeServer.java
   ```

3. **Start the server** (4 GB heap recommended for the PDB):
   ```bash
   java -Xmx4g CubeServer
   ```

4. **Open your browser** and navigate to:
   ```
   http://localhost:8081
   ```

> **First run note:** On first launch the Corner Pattern Database (~88 MB) will be built in the background — this takes 1–2 minutes. The UI is usable immediately; IDA\* will become available once the build completes (a toast notification will appear). Subsequent runs load the cached file in under a second.

---

## 📖 How to Use

1. **Scramble** — Click 🔀 **Scramble** to randomize the cube. Adjust scramble length with the slider.
2. **Pick an algorithm** — Click **Bidirectional BFS** or **Korf's IDA\*** tab above the buttons.
3. **Solve** — Click ✨ **Solve**. The backend calculates the optimal moves and the frontend animates them one by one.
4. **Manual Moves** — Use the **Manual Moves** grid to apply individual moves. The Solve button lights up after any manual move, letting you solve from your custom position.
5. **Custom Position** — Type a notation sequence (e.g. `R U R' U'`) in the **Custom Position** box and click **Apply & Animate**.

---

## 📁 Project Structure

```
rubik_cube_solver/
├── Cube.java             # Cube model — 54-facelet state, geometry-derived move tables
├── Solver.java           # Bidirectional BFS solver
├── IDAStarSolver.java    # Korf's IDA* solver
├── PatternDatabase.java  # Corner PDB — build, cache, and heuristic lookup
├── CubeServer.java       # HTTP server + REST API endpoints
└── web/
    ├── index.html        # App shell
    ├── app.js            # Three.js scene, animations, UI logic
    └── style.css         # Dark-mode glassmorphism design
```

---

## 🔌 REST API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `GET /state` | GET | Current cube state (54-element array) |
| `POST /scramble?n=8` | POST | Scramble with `n` random moves |
| `POST /solve?algorithm=bfs` | POST | Solve with BFS |
| `POST /solve?algorithm=idastar` | POST | Solve with Korf's IDA\* |
| `POST /move?m=R` | POST | Apply a single move incrementally |
| `POST /custom?moves=R+U+R'` | POST | Apply a full custom sequence |
| `POST /reset` | POST | Reset to solved state |
| `GET /movetables` | GET | Permutation tables for frontend animation |

---

## 📄 License

This project is open-source and available under the **MIT License**.
