TLC - tags logs comments (&approvals&entities)

# Entities (not sure where I was going with this
 - create entity
 - read
 - Update
 - destroy

# external entity types
Entities (Ie tables!) that TLC needs to know about

- icuvt
- Source_service (Eg riskarb)
- Source_entity (Eg proposed_changes)

## external entities
Records that TLC needs to know about

- icuvt
- External_entity_type_id
- source_entity_id (Eg a specific value of proposed_changes.id)

# tlcers
- icuvt
- ldap
- 

# Tags

## tags
- icuvt + effectivity
- tag_name

## taggings
- icuvt + feed + effectivicity
- taggable_id ..OR!.. External_entity_id
- tag_id
- Operation: ADD | REMOVE
- ldap
- Service

## api
- create tag
 - tag/untag API
 - is tagged API
 - Was tagged API

# logs
## entries
- timestamp
- log level
- message
- source
- Entity type id
- Entity id

# comments
## threads
- icuv
- token
- Title
- Body
- Creator
- Cached_comments

## comments
- icuv
- Fsi, shard
- comment_token
- thread_id - easily allow 
- parent_comment
- thread_path
- content
- author
- Commented_at

## likes
- icuvt
- Liked_at
- comment_id
- liked_by
- unliked_at

## api
- iframe
- Direct usual APIs:
  - Standard
    - nouns: thread | comment
    - Verbs: create, read, update, destroy
    - Verbs: mk, ls, edit, rm
  - Like [comment_token]
  - 

# approvals
## approveable
The things we might want to approve

- icuv
- token
- external_entity_id (Eg riskarb / propchanges / 1234)
- Approval_context : why should someone approve this rule?
- Approval_rule_token : what rules is this subject to?
- Approval_state
  - INITIAL / PENDING
  - APPROVED
  - EXECUTED
  - DECLINED
- approved_at
- executed_at
- Declined_at
## approvals
Who has approved which things?

- approvable_token
- approved_by
- Approved_at
- Unapproved_at
## approval rule
What is required for a thing to be approved?

- token : “AR-6392”
- Desc : “for approving a new RISK_TR rule”
- Requirement
  - any approval
  - Any non-self approval
  - Approval from >=2 groups
  - Approval from majority of groups
  - Approval from all groups
  - alternatives to “approval”:
    - 
    - Slack notification
    - email notification
    - 
## approval rules to groups
Which groups can do the approving?

- approval rule token
- Group (Eg “ENG”)
- Registry group (“kp-infra”)
- Admins only? (True)
- slack notification channel (#kp-infra-oncall)

