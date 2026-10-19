#!/bin/bash
# Reset limpio: para, borra memoria de sesión (desk.db, eventos),
# mantiene cintas y champion, y relanza todo.
DIR=$(dirname "$0")
"$DIR/stop_all.sh"
rm -f /home/a/work/clj-llm/live/desk.db* /home/a/work/clj-llm/live/clj_events.jsonl /home/a/work/clj-llm/live/clj_orders.jsonl
"$DIR/start_all.sh"
