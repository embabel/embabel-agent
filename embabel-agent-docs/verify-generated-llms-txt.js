const fs = require('fs');
const path = require('path');
const asciidoctor = require('@asciidoctor/core')();

const adocPath = path.resolve(__dirname, '../../src/main/asciidoc/reference/reference.adoc');
const llmsTxtPath = path.resolve(__dirname, '../generated-docs/llms.txt');

if (!fs.existsSync(llmsTxtPath)) {
  console.error('[ERROR] target/generated-docs/llms.txt does not exist.');
  process.exit(1);
}

const llmsContent = fs.readFileSync(llmsTxtPath, 'utf-8');

const doc = asciidoctor.loadFile(adocPath, {
  safe: 'safe',
  attributes: {
    'idprefix': '',
    'idseparator': '-'
  }
});

// Extract ONLY Level 2 (===) sections, which are rendered as clickable links in llms.txt
const linkableSections = doc.findBy({ context: 'section' })
  .filter(sec => sec.getLevel() === 2);

// Extract Level 1 (==) sections, which are rendered as H2 Markdown category headers
const categorySections = doc.findBy({ context: 'section' })
  .filter(sec => sec.getLevel() === 1);

const missingLinks = [];
const missingCategories = [];

// 1. Verify Level 2 Section Links (#anchor)
linkableSections.forEach(section => {
  const sectionId = section.getId();
  if (!llmsContent.includes(`#${sectionId}`)) {
    missingLinks.push({ title: section.getTitle(), id: sectionId });
  }
});

// 2. Verify Level 1 Category Headers (## Category Title)
categorySections.forEach(section => {
  const title = section.getTitle();
  if (!llmsContent.includes(`## ${title}`)) {
    missingCategories.push({ title, id: section.getId() });
  }
});

console.log(`\n--- Cross-Verification Report ---`);
console.log(`Level 1 Categories: ${categorySections.length}`);
console.log(`Level 2 Subsections: ${linkableSections.length}`);

if (missingLinks.length === 0 && missingCategories.length === 0) {
  console.log(`\n[VERIFICATION PASSED] All categories and sub-links are correctly generated in llms.txt.\n`);
} else {
  if (missingCategories.length > 0) {
    console.error(`\n[VERIFICATION FAILED] Missing Category Headers:`);
    missingCategories.forEach(item => console.error(`  - "## ${item.title}"`));
  }
  if (missingLinks.length > 0) {
    console.error(`\n[VERIFICATION FAILED] Missing Subsection Links:`);
    missingLinks.forEach(item => console.error(`  - "${item.title}" (#${item.id})`));
  }
  process.exit(1);
}