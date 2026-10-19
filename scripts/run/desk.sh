#!/bin/bash
# Desk: order gateway + feed relay + UI (port 7777). Never dies.
exec "$(dirname "$0")/supervise.sh" desk /home/a/work/clj-llm/live \
  .venv-grvt/bin/python server.py
