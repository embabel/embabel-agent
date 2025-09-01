CALL db.index.vector.queryNodes($vectorIndex, $topK, $queryVector)
YIELD node AS chunk, score
  WHERE score > $similarityThreshold
RETURN chunk.text AS text, chunk.id AS id,
       score
  ORDER BY score DESC


