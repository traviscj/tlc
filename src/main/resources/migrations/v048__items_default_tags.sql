UPDATE items SET feed_sync_id = NULL, tags = CASE
    WHEN kind = 'property' OR source LIKE 'inforad.prop%' THEN 'apt'
    WHEN kind = 'chore' THEN 'home'
    WHEN kind = 'person' THEN 'family'
    WHEN kind = 'idea' THEN 'idea'
    WHEN kind IN ('deploy', 'commit', 'broken') THEN 'fix'
  END
WHERE tags IS NULL AND (kind IN ('property', 'chore', 'person', 'idea', 'deploy', 'commit', 'broken') OR source LIKE 'inforad.prop%')
