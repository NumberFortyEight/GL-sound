@echo off
setlocal EnableExtensions
rem Build fat jar via Maven and Windows .msi installer via jpackage.
rem Auto-detects JDK 25 in %USERPROFILE%\.jdks and downloads Maven on first run.

pushd "%~dp0\.."

rem ----- locate JDK 25 -----
set "JDK25=%USERPROFILE%\.jdks\openjdk-25.0.2"
if not exist "%JDK25%\bin\javac.exe" (
    for /d %%D in ("%USERPROFILE%\.jdks\*25*") do set "JDK25=%%~fD"
)
if not exist "%JDK25%\bin\javac.exe" (
    echo ERROR: JDK 25 not found under %%USERPROFILE%%\.jdks
    goto :fail
)
set "JAVA_HOME=%JDK25%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Using JDK: %JAVA_HOME%

rem ----- ensure Maven is available locally (outside project target/) -----
set "MVN_VERSION=3.9.9"
set "TOOLS_DIR=%USERPROFILE%\.gl-sound\tools"
set "MVN_HOME=%TOOLS_DIR%\apache-maven-%MVN_VERSION%"
if not exist "%MVN_HOME%\bin\mvn.cmd" call :install_maven
if errorlevel 1 goto :fail
set "MVN=%MVN_HOME%\bin\mvn.cmd"

rem ----- 1. compile + shade -----
echo [1/3] mvn clean package
call "%MVN%" -B -DskipTests clean package
if errorlevel 1 goto :fail
if not exist target\gl-sound.jar (
    echo ERROR: target\gl-sound.jar not found
    goto :fail
)

rem ----- 2. prepare jpackage input -----
if exist target\jpackage-input rmdir /s /q target\jpackage-input
mkdir target\jpackage-input
copy /Y target\gl-sound.jar target\jpackage-input\ >nul

if exist target\dist rmdir /s /q target\dist
mkdir target\dist

rem ----- 3. jpackage (app-image: folder with .exe + bundled JRE, no WiX/Inno required) -----
echo [2/3] jpackage (building app-image)
"%JAVA_HOME%\bin\jpackage" --type app-image --name "GL-Sound" --app-version 0.1.0 --vendor "DeadeanGaffer" --description "Sound monitor for GitLab pipelines" --input target\jpackage-input --main-jar gl-sound.jar --main-class pro.deadeangaffer.glsound.App --java-options "-Dfile.encoding=UTF-8" --dest target\dist
if errorlevel 1 goto :fail

rem ----- 4. optional: build .msi if WiX toolset is installed -----
where /q wix.exe || where /q candle.exe
if not errorlevel 1 (
    echo [3/3] WiX detected, building .msi too
    "%JAVA_HOME%\bin\jpackage" --type msi --name "GL-Sound" --app-version 0.1.0 --vendor "DeadeanGaffer" --description "Sound monitor for GitLab pipelines" --input target\jpackage-input --main-jar gl-sound.jar --main-class pro.deadeangaffer.glsound.App --win-menu --win-menu-group "GL-Sound" --win-shortcut --win-dir-chooser --win-upgrade-uuid 9c54f1b0-3c1d-4d51-9a82-7a4f37e9b001 --java-options "-Dfile.encoding=UTF-8" --dest target\dist
) else (
    echo [3/3] WiX not found, skipping .msi (install WiX 3+ if you want one)
)

echo Done. Output is in target\dist
dir /b target\dist
popd
exit /b 0

:install_maven
echo Downloading Apache Maven %MVN_VERSION% to %TOOLS_DIR% ...
if not exist "%TOOLS_DIR%" mkdir "%TOOLS_DIR%"
set "MVN_ZIP=%TOOLS_DIR%\maven.zip"
set "MVN_URL=https://archive.apache.org/dist/maven/maven-3/%MVN_VERSION%/binaries/apache-maven-%MVN_VERSION%-bin.zip"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest '%MVN_URL%' -OutFile '%MVN_ZIP%'; Expand-Archive -Force '%MVN_ZIP%' -DestinationPath '%TOOLS_DIR%'; Remove-Item '%MVN_ZIP%'"
if errorlevel 1 exit /b 1
exit /b 0

:fail
echo Build failed.
popd
exit /b 1
