#!/usr/bin/env bash
# Acceptance runner for the CSV sequence validator.
# Directs all build caches and temp files to the D drive worktree.
set -euo pipefail

WORKTREE="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="/c/Users/18375/AppData/Local/Programs/Microsoft/jdk-25.0.3.9-hotspot"
export M2_HOME="/d/tools/apache-maven-3.9.11"
export PATH="$JAVA_HOME/bin:$M2_HOME/bin:$PATH"
export TEMP="$WORKTREE/.build/tmp"
export TMP="$WORKTREE/.build/tmp"
mkdir -p "$TEMP"
export MAVEN_OPTS="-Duser.language=en -Duser.country=US -Djava.io.tmpdir=$TEMP -Dmaven.repo.local=$WORKTREE/.build/m2repo"

cd "$WORKTREE"
mvn test -Dtest=CSVSequenceValidatorTest
