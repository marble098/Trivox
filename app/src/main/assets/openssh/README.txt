Run the GitHub Actions workflow "Build OpenSSH Android assets" and replace this
openssh directory with the generated artifact before building a release that
uses OpenSSH. The workflow creates arm64-v8a, armeabi-v7a and x86_64 client
binaries, runtime libraries/configuration, and a SHA-256 manifest compiled for
/data/data/com.trivox.client/files/usr.
