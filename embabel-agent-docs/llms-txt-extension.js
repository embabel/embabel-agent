const fs = require('fs');
const path = require('path');
const asciidoctor = require('@asciidoctor/core')();
const docVersion = process.env.DOC_VERSION;

if (!docVersion) {
  throw new Error('[ERROR] DOC_VERSION environment variable is not defined.');
}
// Register the AST Extension
asciidoctor.Extensions.register(function () {
  this.treeProcessor(function () {
    const self = this;

    self.process(function (document) {
      const siteTitle = 'Embabel Agent Framework';
      const siteSummary = 'Embabel (Em-BAY-bel) is a framework for authoring agentic flows on the JVM that seamlessly mix LLM-prompted interactions with code and domain models.';
      const baseUrl = `https://docs.embabel.com/embabel-agent/guide/${docVersion}`;

      let llmsContent = `# ${siteTitle}\n\n> ${siteSummary}\n\n`;

      const sections = document.findBy({ context: 'section' });

      sections.forEach(section => {
        const level = section.getLevel();
        const title = section.getTitle();
        const id = section.getId();

        // Level 1 = H2 in AsciiDoc (== Title)
        if (level === 1) {
          llmsContent += `\n## ${title}\n\n`;
        }
        // Level 2 = H3 in AsciiDoc (=== Subtitle)
        else if (level === 2) {
          const pageUrl = `${baseUrl}/#${id}`;
          llmsContent += `- [${title}](${pageUrl})\n`;
        }
      });

      // Append Examples Section
      llmsContent += `\n## Examples\n\n`;
      llmsContent += `- [Embabel Agent Examples Repository](https://github.com/embabel/embabel-agent-examples): Collection of sample projects and integration demonstrations for Embabel Agent.\n`;

      // Append Cookbook Section
      llmsContent += `\n## Cookbook\n\n`;
      llmsContent += `- [Embabel Cookbook](https://github.com/embabel/embabel-cookbook): Practical recipes, patterns, and code snippets for building agentic workflows.\n`;

      // Write to target folder where Maven packages static site assets
      const outputDir = path.resolve(__dirname, '../generated-docs');
      if (!fs.existsSync(outputDir)) {
        fs.mkdirSync(outputDir, { recursive: true });
      }

      fs.writeFileSync(path.join(outputDir, 'llms.txt'), llmsContent, 'utf-8');
      console.log('[llms.txt] Successfully generated target/generated-docs/llms.txt');

      return document;
    });
  });
});

// Run conversion
asciidoctor.convertFile(
  path.resolve(__dirname, '../../src/main/asciidoc/reference/reference.adoc'),
  {
    safe: 'safe',
    to_file: false, // Prevents creating reference.html in source tree.
    // To make IDs match Standard Web Anchors
    attributes: {
      'idprefix': '',      // Removes leading underscore
      'idseparator': '-'   // Uses hyphens instead of underscores
    }
  }
);