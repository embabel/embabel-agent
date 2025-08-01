MATCH (existing:$($labels))
WHERE existing.name IS NOT NULL
WITH existing,
     apoc.text.distance(toLower(existing.name), toLower($name)) AS nameDistance
  WHERE nameDistance <= 3
RETURN existing,
       labels(existing) AS labels,
       existing.name as name,
       existing.id AS id,
       existing.description AS description,
       nameDistance,
       (1.0 - (nameDistance / toFloat(size($name)))) AS nameScore,
       (1.0 - (nameDistance / toFloat(size($name)))) * 0.7 AS combinedScore
  ORDER BY combinedScore DESC
  LIMIT $limit;