@echo off
cd /d "%~dp0"
mise exec -- .\gradlew.bat :gradle-plugin:publishToMavenLocal
