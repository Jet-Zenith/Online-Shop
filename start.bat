@echo off
echo Starting Redis Online Shop...
echo.

echo Checking if Redis is running...
redis-cli ping
if %errorlevel% neq 0 (
    echo Redis is not running. Please start Redis first.
    echo You can start Redis using:
    echo   redis-server (on Windows with WSL or Redis for Windows)
    echo   or
    echo   Start the Redis service from your services
    pause
    exit /b 1
)

echo Redis is running!
echo.

echo Building and starting the application...
mvn clean spring-boot:run

pause