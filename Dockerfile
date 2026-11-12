# Stage 1: modern cmdline-tools need JDK 11 to run sdkmanager, so fetch
# the SDK pieces here.
FROM docker.io/library/eclipse-temurin:11-jdk AS sdk

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip wget ca-certificates \
 && rm -rf /var/lib/apt/lists/*

ENV ANDROID_SDK_ROOT=/opt/android-sdk

RUN wget -q https://dl.google.com/android/repository/commandlinetools-linux-9862592_latest.zip -O /tmp/cmdline.zip \
 && unzip -q /tmp/cmdline.zip -d /opt/cmdline \
 && mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools" \
 && mv /opt/cmdline/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest" \
 && yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null \
 && "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
      "platform-tools" "platforms;android-29" "build-tools;29.0.3" \
 && rm -rf /tmp/cmdline.zip /opt/cmdline

# Stage 2: the actual toolchain -- JDK 8 + Gradle 6.5 + AGP 4.1.3 is the
# last combo that happily targets minSdk 8 / targetSdk 10 (Gingerbread era).
FROM docker.io/library/eclipse-temurin:8-jdk

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip wget ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY --from=sdk /opt/android-sdk /opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk

# ---- Gradle 6.5 ----
RUN wget -q https://services.gradle.org/distributions/gradle-6.5-bin.zip -O /tmp/gradle.zip \
 && unzip -q /tmp/gradle.zip -d /opt \
 && rm /tmp/gradle.zip
ENV PATH="/opt/gradle-6.5/bin:${PATH}"

WORKDIR /project
CMD ["gradle", "--no-daemon", "assembleDebug"]
