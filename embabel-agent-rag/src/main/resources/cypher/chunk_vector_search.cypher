CALL db.index.vector.queryNodes('spring-ai-document-index', $topK, $queryVector)
YIELD node AS chunk, score
WHERE score > $similarityThreshold
RETURN chunk.text as text, chunk.id as id,
       score
ORDER BY score DESC

