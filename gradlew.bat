@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Java 17+ uses a local Unix-domain socket to wake its Windows NIO selector.
@rem Sandboxed tools may not be allowed to create that socket in the inherited user TEMP,
@rem even though ordinary TCP loopback works. Keep Java's temporary IPC inside Gradle's
@rem ignored, workspace-local directory so command-line builds remain usable there.
set GRADLE_JAVA_TMP=%APP_HOME%\.gradle\java-tmp
if not exist "%GRADLE_JAVA_TMP%" mkdir "%GRADLE_JAVA_TMP%" >NUL 2>&1
if exist "%GRADLE_JAVA_TMP%" set TEMP=%GRADLE_JAVA_TMP%
if exist "%GRADLE_JAVA_TMP%" set TMP=%GRADLE_JAVA_TMP%

@rem Android Studio stores #GRADLE_LOCAL_JAVA_HOME in .gradle/config.properties.
@rem The stock Windows wrapper ignores it and can therefore pick a newer, unsupported JDK
@rem from the host process. Reuse the same local JDK for CLI builds when that file exists.
set GRADLE_LOCAL_JAVA_HOME=
if not exist "%APP_HOME%\.gradle\config.properties" goto localJavaHomeReady
setlocal EnableDelayedExpansion
set GRADLE_LOCAL_JAVA_HOME_INNER=
for /F "usebackq tokens=1,* delims==" %%G in ("%APP_HOME%\.gradle\config.properties") do (
    if "%%G"=="java.home" set GRADLE_LOCAL_JAVA_HOME_INNER=%%H
)
if not defined GRADLE_LOCAL_JAVA_HOME_INNER goto localJavaHomeMissing
set GRADLE_LOCAL_JAVA_HOME_INNER=!GRADLE_LOCAL_JAVA_HOME_INNER:\:=:!
set GRADLE_LOCAL_JAVA_HOME_INNER=!GRADLE_LOCAL_JAVA_HOME_INNER:\\=\!
for /F "delims=" %%I in ("!GRADLE_LOCAL_JAVA_HOME_INNER!") do (
    endlocal
    set GRADLE_LOCAL_JAVA_HOME=%%~I
)
goto localJavaHomeResolved
:localJavaHomeMissing
endlocal
:localJavaHomeResolved
if exist "%GRADLE_LOCAL_JAVA_HOME%\bin\java.exe" set JAVA_HOME=%GRADLE_LOCAL_JAVA_HOME%
:localJavaHomeReady

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar


@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
