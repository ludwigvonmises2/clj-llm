#!/bin/bash
# Hot learner: champion.edn writer; the brain hot-reloads it.
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/supervise.sh" learner /home/a/work/clj-llm "$DIR/learner-loop.sh"
