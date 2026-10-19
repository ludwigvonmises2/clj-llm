#!/bin/bash
# Hot learner: champion.edn writer; brain and engines hot-reload it.
exec "$(dirname "$0")/supervise.sh" learner /home/a/work/clj-llm \
  "$(dirname "$0")/learner-loop.sh"
