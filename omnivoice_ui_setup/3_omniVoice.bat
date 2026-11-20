
@echo off
setlocal
title OmniVoice TTS

set "ENV_NAME=omnivoice"
set "HOST=127.0.0.1"
set "PORT=8001"

:: Find Conda
set "CONDA_BAT="

for /f "delims=" %%F in ('where conda.bat 2^>nul') do (
    if not defined CONDA_BAT set "CONDA_BAT=%%F"
)

:: Check common Conda installation directories
if not defined CONDA_BAT (
    for %%F in (
        "%USERPROFILE%\miniconda3\condabin\conda.bat"
        "%USERPROFILE%\anaconda3\condabin\conda.bat"
        "%USERPROFILE%\miniforge3\condabin\conda.bat"
        "%LOCALAPPDATA%\miniconda3\condabin\conda.bat"
        "%LOCALAPPDATA%\anaconda3\condabin\conda.bat"
        "%ProgramData%\miniconda3\condabin\conda.bat"
        "%ProgramData%\anaconda3\condabin\conda.bat"
    ) do (
        if not defined CONDA_BAT (
            if exist "%%~F" set "CONDA_BAT=%%~F"
        )
    )
)

if not defined CONDA_BAT (
    echo ERROR: Conda installation not found.
    goto :error
)

echo Activating Conda environment: %ENV_NAME%
call "%CONDA_BAT%" activate %ENV_NAME%

if errorlevel 1 (
    echo ERROR: Failed to activate %ENV_NAME%.
    goto :error
)

echo.
echo Starting OmniVoice...
echo Web UI: http://%HOST%:%PORT%
echo.

omnivoice-demo --ip %HOST% --port %PORT%

echo.
echo OmniVoice stopped.
pause
exit /b 0

:error
echo.
pause
exit /b 1
