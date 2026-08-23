@echo off
title Rubik's Cube 3D Solver
cd /d "%~dp0"

echo.
echo  ==========================================
echo   Rubik's Cube 3D Solver – Build ^& Run
echo  ==========================================
echo.

echo [1/2] Compiling Java sources...
javac -d . Cube.java Solver.java CubeServer.java
if errorlevel 1 (
    echo.
    echo  ERROR: Compilation failed. Check the output above.
    pause
    exit /b 1
)
echo       Done.

echo.
echo [2/2] Starting server on http://localhost:8080
echo       Press Ctrl+C to stop.
echo.

:: Open browser after a short delay (optional – comment out if unwanted)
start "" /B cmd /c "timeout /t 2 /nobreak >nul && start http://localhost:8080"

java CubeServer

pause
