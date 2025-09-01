CREATE VECTOR INDEX `spring-ai-document-index` IF NOT EXISTS FOR (n:Document) ON (n.embedding)
OPTIONS {indexConfig: {
`vector.dimensions`: 384,
`vector.similarity_function`: 'cosine'
}};
