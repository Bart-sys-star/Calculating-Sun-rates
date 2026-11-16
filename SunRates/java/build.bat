@echo off
rem Compiles the calculator into out\ and packages FusionRates.jar (needs a JDK 8 or newer).
cd /d "%~dp0"
where javac >nul 2>nul || (
  echo javac was not found. Install a JDK, for example Eclipse Temurin from https://adoptium.net
  exit /b 1
)
if not exist out mkdir out
rem --release 8 keeps the jar runnable on the older Java 8 runtime that may also be installed
javac --release 8 -Xlint:-options -encoding UTF-8 -d out src\*.java || exit /b 1
jar cfe FusionRates.jar FusionRatesApp -C out . || exit /b 1
echo Built FusionRates.jar
echo   Window:   java -jar FusionRates.jar
echo   Console:  java -jar FusionRates.jar --cli
echo   Quick:    java -jar FusionRates.jar --cli 10 15.7 20 30
