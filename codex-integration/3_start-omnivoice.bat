@echo off
setlocal

cd /d "%~dp0.." || exit /b 1

if not defined TALIA_TTS_PYTHON set "TALIA_TTS_PYTHON=%USERPROFILE%\miniconda3\envs\omnivoice\python.exe"
if not exist "%TALIA_TTS_PYTHON%" (
    echo OmniVoice Python was not found at "%TALIA_TTS_PYTHON%".
    echo Set TALIA_TTS_PYTHON to the python.exe in your OmniVoice environment.
    pause
    exit /b 1
)

if not defined HF_HUB_OFFLINE set "HF_HUB_OFFLINE=1"

echo Starting OmniVoice at http://127.0.0.1:8001 ...
"%TALIA_TTS_PYTHON%" -B tts\serve.py
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo OmniVoice exited with code %EXIT_CODE%. See the error above.
    pause
)
exit /b %EXIT_CODE%
