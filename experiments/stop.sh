#!/usr/bin/env bash
# Stops the experiment stack. Its database volume is kept; add -v to delete it:
#   experiments/stop.sh
#   experiments/stop.sh -v

source "$(dirname "$0")/lib.sh"

exp_compose down "$@"
log "Experiment stack stopped"
