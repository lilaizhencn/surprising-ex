#!/usr/bin/env bash
exec "$(dirname "${BASH_SOURCE[0]}")/kafka-docker-cli" kafka-console-producer.sh "$@"
