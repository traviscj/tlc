#!/usr/bin/env bash
# Entities: TLC stores nothing about your rows except the triple (service, entity, id).
. "$(dirname "$0")/_lib.sh"
E="sentinel/proposed_changes/e$RUN"

say "Entity types are tables TLC has been told about. Unregistered ones are refused, so a typo can't mint a table."
run --as alice type ls
refused --as alice tag add "sentinel/propsed_changes/1" urgent

say "Entities (rows) are never pre-declared: the first write that mentions one creates it."
refused --as alice entity show "$E"
run --as alice tag add "$E" demo
run --as alice entity show "$E"

say "CRUD, both verb spellings. 'url' comes from the type's url_template."
run --as alice entity update "$E" --name "raise ATO_VELOCITY velocity limit"
run --as alice --json entity read "$E"
run --as alice entity rm "$E"
note "destroy is soft: history stays readable, and the next write revives the record"
run --as alice tag is "$E" demo
run --as alice log add "$E" "still alive in sentinel"
run --as alice entity show "$E"

say "Everyone who has acted is a tlcer."
run --as alice --json me
