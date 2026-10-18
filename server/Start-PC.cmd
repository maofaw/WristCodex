@echo off
cd /d "%~dp0"
if exist "..\.venv\Scripts\python.exe" (
  "..\.venv\Scripts\python.exe" pc_listener.py
) else (
  py -3 pc_listener.py
)
pause
