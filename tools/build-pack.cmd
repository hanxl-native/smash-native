@echo off
REM Build SmashUltimate.nup + pack.zip on the PC, inside WSL2 (see BUILD-PACK.md).
REM Usage: build-pack.cmd "C:\path\game.xci" "C:\path\update.nsp" C:\path\dlc\*.nsp
setlocal
wsl --cd "%~dp0" bash ./build-pack.sh %*
if errorlevel 1 pause
