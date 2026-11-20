@echo off
setlocal

title OmniVoice TTS - Local Model Backup
set "ENV_NAME=omnivoice"
set "MODEL_DIR=C:\AI\Models\OmniVoice"
set "HOST=127.0.0.1"
set "PORT=8001"

:: Detect Conda installed in PATH or a typical Windows location.
set "CONDA_BAT="
for /f "delims=" %%F in ('where conda.bat 2^>nul') do (
    if not defined CONDA_BAT set "CONDA_BAT=%%F"
)
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
        if not defined CONDA_BAT if exist "%%~F" set "CONDA_BAT=%%~F"
    )
)
if not defined CONDA_BAT (
    echo ERROR: Conda could not be found.
    echo Edit CONDA_BAT in this file if you installed Conda in a custom path.
    goto :error
)

if not exist "%MODEL_DIR%\model.safetensors" (
    echo ERROR: Main model weights are missing from "%MODEL_DIR%".
    echo First download the backup with:
    echo hf buckets sync hf://buckets/desvlat/OmniVoice-bucket "%MODEL_DIR%"
    goto :error
)
if not exist "%MODEL_DIR%\audio_tokenizer\model.safetensors" (
    echo ERROR: Audio tokenizer weights are missing from "%MODEL_DIR%\audio_tokenizer".
    goto :error
)

call "%CONDA_BAT%" activate "%ENV_NAME%"
if errorlevel 1 (
    echo ERROR: Could not activate Conda environment "%ENV_NAME%".
    goto :error
)

echo Starting OmniVoice with LOCAL model weights...
echo Model: "%MODEL_DIR%"
echo URL: http://%HOST%:%PORT%
echo.

:: --no-asr prevents a separate Whisper download.
:: Remove --no-asr to allow automatic transcription of reference audio.
omnivoice-demo --model "%MODEL_DIR%" --no-asr --ip %HOST% --port %PORT%
set "RESULT=%ERRORLEVEL%"

echo.
echo OmniVoice stopped with exit code %RESULT%.
pause
exit /b %RESULT%

:error
echo.
pause
exit /b 1
