#!/bin/bash
# Recorder: sole owner of venue WS, binary tapes. Never dies.
exec "$(dirname "$0")/supervise.sh" recorder /home/a/work/clj-llm/live \
  .venv-grvt/bin/python recorder.py
