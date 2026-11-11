"""Tiny formatters for the curl-based bits of the demos: reads JSON on stdin."""
import json, sys

d = json.load(sys.stdin)
if sys.argv[1] == "notifications":
    for n in reversed(d["notifications"]):
        print(f"   {n['status']:<7} {n['channel']:<5} {n['destination']:<18} {n['event']:<9} {n['body'][:95]}")
elif sys.argv[1] == "taggings":
    for e in d["entries"]:
        print(f"   fsi={int(e['feed_sync_id']):<4} shard={int(e['shard']):<2} {e['operation']:<6} {e['tag']:<14} {e['target']}")
    print("   cursor", int(d["cursor"]))
