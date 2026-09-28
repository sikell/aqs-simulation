@echo off
REM Run analyze_mass_run.py for each mass-run-results* folder (Windows CMD)

setlocal EnableExtensions
cd /d "%~dp0"

set "PYTHON_SCRIPT=%~dp0mass-run-analysis\analyze_mass_run.py"
set "DRY_RUN=0"
if /I "%~1"=="--dry-run" set "DRY_RUN=1"

echo.
echo ------------------------------------------------------------
echo   Mass-Run Analysis Batch Runner
echo ------------------------------------------------------------
echo.

if not exist "%PYTHON_SCRIPT%" (
    echo [ERROR] Python script not found:
    echo         %PYTHON_SCRIPT%
    exit /b 1
)

set /a count=0
set /a failed=0

@for /d %%F in (mass-run-results*) do (
    @if exist "%%F\mass-run-results.csv" (
        @set /a count+=1 >nul
        @echo.
        @call echo [%%count%%] Analyzing: %%F
        @echo ------------------------------------------------------------

        @if "%DRY_RUN%"=="1" (
            @echo python "%PYTHON_SCRIPT%" --input-csv "%%F\mass-run-results.csv" --output-dir "%%F\analysis"
        ) else (
            @python "%PYTHON_SCRIPT%" --input-csv "%%F\mass-run-results.csv" --output-dir "%%F\analysis"
            @if errorlevel 1 (
                @set /a failed+=1 >nul
                @echo [ERROR] Failed: %%F
            ) else (
                @echo [OK] Completed: %%F
            )
        )
    ) else (
        @echo [SKIP] %%F\mass-run-results.csv not found
    )
)

echo.
echo ------------------------------------------------------------
if "%DRY_RUN%"=="1" (
    echo Summary: %count% runnable folder(s) found. Dry-run only.
) else (
    echo Summary: %count% folder(s) processed, %failed% failed
)
echo ------------------------------------------------------------
echo.

if "%DRY_RUN%"=="1" exit /b 0
if %failed% gtr 0 (
    exit /b 1
)

echo All analyses completed successfully.
exit /b 0

