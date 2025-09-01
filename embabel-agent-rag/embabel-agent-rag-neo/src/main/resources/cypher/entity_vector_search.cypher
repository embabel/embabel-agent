CALL db.index.vector.queryNodes('entity_embeddings', $topK, $queryVector)
YIELD node AS m, score
WHERE score > $similarityThreshold
RETURN m AS match, m.name as name, m.description as description, m.id AS id, labels(m) AS labels,
       score
ORDER BY score DESC

