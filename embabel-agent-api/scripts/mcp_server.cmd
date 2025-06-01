@echo off
setlocal

call .\support\check_env.bat

if errorlevel 1 (
    echo Environment check failed. Exiting...
    exit /b 1
)

cd ..

set SPRING_PROFILES_ACTIVE=web,severance

cmd /c mvn -P agent-examples-kotlin -Dmaven.test.skip=true spring-boot:run

endlocal