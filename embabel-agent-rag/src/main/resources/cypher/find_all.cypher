MATCH (existing:$($labels))
RETURN existing,
       labels(existing) AS labels,
       existing.name as name,
       existing.id AS id,
       existing.description AS description,
  LIMIT $limit;