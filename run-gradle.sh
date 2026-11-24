#!/system/bin/sh
# Run Gradle in the app-uid tier.
#
# The toolchain's ELF binaries live under app_data_file, whose SELinux
# policy denies direct exec even with the mode bit set. A small shell
# wrapper at jdkwrap/bin/java reroutes the real ELF through the system
# linker and is itself directly executable. The Gradle wrapper, its
# daemon, and the Kotlin compiler all inherit a working JVM because
# org.gradle.java.home points at the wrapper directory.
export JAVA_HOME=/data/user/0/com.androidharness.app.debug/files/jdkwrap
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-/data/user/0/com.androidharness.app.debug/files/gradle-app}
export TMPDIR=/data/local/tmp/androidharness-scratch
export GRADLE_OPTS="-Xmx1g -Xms256m -Dorg.gradle.java.home=/data/user/0/com.androidharness.app.debug/files/jdkwrap"
cd /storage/emulated/0/Download/AndroidHarness-Recovery/AndroidHarness-source
exec /system/bin/linker64 /data/user/0/com.androidharness.app.debug/files/linux/bin/bash gradlew "$@"
