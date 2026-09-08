@echo off
setlocal DisableDelayedExpansion

set "SCRIPT_DIR=%~dp0."

:: Prioritize missing canonical peer-app.jar + existing journal recovery over developer JAR fallback
if not exist "%SCRIPT_DIR%\peer-app.jar" (
    if exist "%SCRIPT_DIR%\.p2p-update\helper.jar" (
        if exist "%SCRIPT_DIR%\.p2p-update\transaction.properties" (
            echo Interrupted update detected. Running recovery...
            java -Djava.awt.headless=false -Dfile.encoding=UTF-8 -cp "%SCRIPT_DIR%\.p2p-update\helper.jar" vn.edu.p2p.peer.update.UpdateInstaller --recover "%SCRIPT_DIR%"
            if errorlevel 1 (
                echo Error: Recovery failed with non-zero exit code. >&2
                exit /b 1
            )
            if not exist "%SCRIPT_DIR%\peer-app.jar" (
                echo Error: Recovery did not restore canonical peer-app.jar. >&2
                exit /b 1
            )
        )
    )
)

:: Resolve peer-app.jar location
set "JAR="
if exist "%SCRIPT_DIR%\peer-app.jar" (
    set "JAR=%SCRIPT_DIR%\peer-app.jar"
) else if exist "%SCRIPT_DIR%\peer-app\target\peer-app.jar" (
    set "JAR=%SCRIPT_DIR%\peer-app\target\peer-app.jar"
) else if exist ".\peer-app.jar" (
    set "JAR=.\peer-app.jar"
) else if exist ".\peer-app\target\peer-app.jar" (
    set "JAR=.\peer-app\target\peer-app.jar"
) else (
    echo Error: peer-app.jar not found. >&2
    echo Run 'mvn clean package' to build it, or place peer-app.jar beside this script. >&2
    exit /b 1
)

:: Resolve configuration argument
set "CONFIG=%~1"
if "%CONFIG%"=="" set "CONFIG=peer.properties"
if not exist "%CONFIG%" (
    if exist "%SCRIPT_DIR%\%CONFIG%" (
        set "CONFIG=%SCRIPT_DIR%\%CONFIG%"
    )
)

echo Launching: java -Djava.awt.headless=false -Dfile.encoding=UTF-8 -jar "%JAR%" "%CONFIG%"
java -Djava.awt.headless=false -Dfile.encoding=UTF-8 -jar "%JAR%" "%CONFIG%"
set "EXITCODE=%ERRORLEVEL%"
exit /b %EXITCODE%
