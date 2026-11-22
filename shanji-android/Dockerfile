# 闪记 Android 构建环境镜像
# SDK 组件从腾讯镜像预下载（COPY 进镜像，构建时容器不访问 Google）
FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_HOME=/opt/android-sdk
ENV GRADLE_HOME=/opt/gradle
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${GRADLE_HOME}/bin:${PATH}"

# 基础镜像无 unzip；apt 换阿里云源保证容器内可达
RUN sed -i 's/archive.ubuntu.com/mirrors.aliyun.com/g; s/security.ubuntu.com/mirrors.aliyun.com/g' /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

COPY sdk/cmdline-tools.zip sdk/platform-36.zip sdk/platform-tools.zip sdk/build-tools.zip /tmp/

RUN mkdir -p ${ANDROID_HOME}/licenses && \
    printf '8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a217b660f50d5ae4d79dbf0a3bee5c3a1\n' > ${ANDROID_HOME}/licenses/android-sdk-license && \
    echo '84831b9409646a918e30573bab4c9c91346d8abd' > ${ANDROID_HOME}/licenses/android-sdk-preview-license && \
    mkdir -p /tmp/ct ${ANDROID_HOME}/cmdline-tools ${ANDROID_HOME}/platforms ${ANDROID_HOME}/build-tools /tmp/bt && \
    unzip -q /tmp/cmdline-tools.zip -d /tmp/ct && \
    mv /tmp/ct/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    unzip -q /tmp/platform-36.zip -d ${ANDROID_HOME}/platforms && \
    unzip -q /tmp/platform-tools.zip -d ${ANDROID_HOME} && \
    unzip -q /tmp/build-tools.zip -d /tmp/bt && \
    mv /tmp/bt/android-16 ${ANDROID_HOME}/build-tools/36.0.0 && \
    chmod -R +x ${ANDROID_HOME}/build-tools/36.0.0 ${ANDROID_HOME}/cmdline-tools/latest/bin ${ANDROID_HOME}/platform-tools && \
    rm -rf /tmp/ct /tmp/bt /tmp/*.zip

# Gradle 8.11.1（腾讯镜像）
RUN curl -sL -o /tmp/gradle.zip \
      https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && mv /opt/gradle-8.11.1 /opt/gradle \
    && rm /tmp/gradle.zip

# 预生成固定的 debug 签名（保证每次构建 APK 签名一致，可覆盖安装）
RUN mkdir -p /root/.android && keytool -genkeypair -keystore /root/.android/debug.keystore \
      -storepass android -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
      -keyalg RSA -keysize 2048 -validity 10000

WORKDIR /work
