# Rubik's Cube 3D Solver

A full-stack, interactive 3D Rubik's Cube application featuring a beautiful glassmorphic UI, a 3D animated cube built with Three.js, and a fast, custom Bidirectional Breadth-First Search (BFS) solver written in Java.

## ✨ Features

- **Interactive 3D Graphics**: Fully animated, 3D Rubik's Cube using Three.js with realistic lighting, shadows, and orbit controls.
- **Fast Solver**: Custom Bidirectional BFS algorithm written in Java that finds optimal solutions extremely fast.
- **Scramble & Solve**: Instantly scramble the cube with a configurable number of moves and watch the algorithm solve it piece by piece.
- **Custom States**: Enter any custom move sequence (e.g. `R U R' F2 L D2 B'`) to set the cube to a specific state, or try presets like the "Sexy Move", "Sune", or "H-perm".
- **Real-time Statistics**: Tracks your solve times, move counts, and personal bests.

## 🛠️ Technologies Used

- **Backend**: Java (pure JDK, no external dependencies). Uses `com.sun.net.httpserver` for a lightweight REST API.
- **Frontend**: HTML5, Vanilla JavaScript, CSS3.
- **3D Engine**: Three.js (imported via CDN).
- **Styling**: Custom CSS with a sleek, responsive, dark-mode glassmorphism design.

## 🚀 Getting Started

### Prerequisites
- **Java Development Kit (JDK) 21** (or higher) installed on your system.
- A modern web browser.

### Running Locally (Windows)

1. Clone this repository:
   ```bash
   git clone https://github.com/satyamanand360/rubik_cube_solver.git
   cd rubik_cube_solver
   ```
2. Run the provided batch script:
   ```cmd
   compile_and_run.bat
   ```
   *This script will compile the Java source files, start the background REST server on port 8080, and automatically open your default web browser.*

### Running Manually (Any OS)

1. Compile the Java files:
   ```bash
   javac -d . Cube.java Solver.java CubeServer.java
   ```
2. Start the server:
   ```bash
   java CubeServer
   ```
3. Open your browser and navigate to: `http://localhost:8080`

## 🎮 How to Use

1. **Scramble**: Click the 🔀 **Scramble** button to randomize the cube. You can adjust the scramble length and animation speed using the sliders.
2. **Solve**: Once scrambled, click the ✨ **Solve** button. The Java backend will calculate the optimal solution and the frontend will animate the moves.
3. **Custom Position**: Scroll down to the **Custom Position** panel, type in a sequence of standard Rubik's Cube notation moves (like `R U R' U'`), and hit **Apply & Animate**. You can then solve it from that exact state!

## 📜 License

This project is open-source and available under the MIT License.
