#!/bin/bash
# Desk: order gateway + feed relay + UI (port 7777). Never dies.
DIR="$(cd "$(dirname "$0")" && pwd)"
set -a; [ -f /home/a/work/clj-llm/live/.env.grvt ] && . /home/a/work/clj-llm/live/.env.grvt; set +a
exec "$DIR/supervise.sh" desk /home/a/work/clj-llm/live .venv-grvt/bin/python server.py
