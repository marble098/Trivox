#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "${JAVA_HOME:-}" ]; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
fi

if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVACMD" ]; then
    echo "ERROR: Java 17 was not found. Set JAVA_HOME to a JDK 17 installation." >&2
    exit 1
fi

exec "$JAVACMD" "-Dorg.gradle.appname=gradlew" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
