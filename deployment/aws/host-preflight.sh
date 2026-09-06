#!/usr/bin/env bash
# Read-only readiness collection. Run before allocating a formal measurement window.
set -euo pipefail
java -version
mvn -version
uname -a
lscpu
free -h
swapon --show
df -h / /dev/shm
sysctl net.core.rmem_max net.core.wmem_max net.ipv4.ip_local_port_range
ulimit -n
ip -br address
ip route
if [[ -r /sys/fs/cgroup/cpu.stat ]]; then cat /sys/fs/cgroup/cpu.stat; fi
if [[ -r /sys/fs/cgroup/cpu.max ]]; then cat /sys/fs/cgroup/cpu.max; fi
printf 'No performance qualification has been performed by this command.\n'
