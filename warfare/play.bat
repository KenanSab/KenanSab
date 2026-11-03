@echo off
rem Double-click to update and start Warfare. Keep this window open while you play.
cd /d "%~dp0"
echo Updating...
git pull
echo Starting Warfare...
call gradlew.bat :desktop:run
pause
