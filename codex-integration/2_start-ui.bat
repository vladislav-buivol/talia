@echo off
setlocal

cd /d "%~dp0..\frontend" || exit /b 1

where npm.cmd >nul 2>&1
if not errorlevel 1 (
    set "PACKAGE_MANAGER=npm"
) else (
    where pnpm.cmd >nul 2>&1
    if errorlevel 1 (
        echo Node.js and npm or pnpm are required to start the UI.
        exit /b 1
    )
    set "PACKAGE_MANAGER=pnpm"
)

if not exist "node_modules\vite\bin\vite.js" (
    echo Installing frontend dependencies...
    call %PACKAGE_MANAGER% install
    if errorlevel 1 exit /b 1
)

echo Starting Talia UI. Start Codex and Talia separately.
call %PACKAGE_MANAGER% run dev
exit /b %errorlevel%
