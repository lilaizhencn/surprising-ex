#!/bin/bash
set -euxo pipefail
ROLE=${1:-build}
case "$ROLE" in build|runtime) ;; *) exit 2 ;; esac
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y git curl ca-certificates python3 unzip sysstat jq
mkdir -p /opt/java /opt/maven
curl -fsSL 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz' -o /tmp/jdk.tar.gz
printf '%s  /tmp/jdk.tar.gz\n' dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e | sha256sum -c -
tar -xzf /tmp/jdk.tar.gz -C /opt/java --strip-components=1
curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz -o /tmp/maven.tar.gz
curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz.sha512 -o /tmp/maven.sha512
python3 - <<'PY'
import hashlib
assert hashlib.file_digest(open('/tmp/maven.tar.gz','rb'),'sha512').hexdigest()==open('/tmp/maven.sha512').read().split()[0]
PY
tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1
cat >/etc/profile.d/surprising-java.sh <<'ENV'
export JAVA_HOME=/opt/java
export MAVEN_HOME=/opt/maven
export PATH=/opt/java/bin:/opt/maven/bin:$PATH
export MAVEN_OPTS='-Xms256m -Xmx1536m'
ENV
ln -sf /opt/java/bin/java /usr/local/bin/java
ln -sf /opt/maven/bin/mvn /usr/local/bin/mvn
sudo -u ubuntu git config --global pull.ff only
sudo -u ubuntu git config --global init.defaultBranch master
sudo -u ubuntu git config --global fetch.prune true
sudo -u ubuntu git config --global credential.helper ''
if [[ "$ROLE" == build ]]; then
cat >/etc/systemd/system/surprising-autostop.service <<'UNIT'
[Unit]
Description=Stop build host to preserve AWS credits
[Service]
Type=oneshot
ExecStart=/usr/sbin/shutdown -h now
UNIT
cat >/etc/systemd/system/surprising-autostop.timer <<'UNIT'
[Unit]
Description=Stop build host six hours after boot
[Timer]
OnBootSec=6h
Unit=surprising-autostop.service
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now surprising-autostop.timer
fi
. /etc/profile.d/surprising-java.sh
java -version
mvn -version
if [[ "$ROLE" == build ]]; then
sudo -u ubuntu mkdir -p /home/ubuntu/work
sudo -u ubuntu git clone --branch master --single-branch https://github.com/lilaizhencn/surprising-ex.git /home/ubuntu/work/surprising-ex
touch /var/lib/surprising-build-ready
fi
