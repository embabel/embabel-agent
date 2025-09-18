# Documentation

To build:

```aiignore
mvn generate-resources
```

Results will be in `target/generated-docs`.

To build Markdown, install Docling:

```bash
pip install docling
```

Then run:

```bash
mvn generate-resources -Dasciidoctor.attributes='toc!'

docling  ./target/generated-docs/index.pdf --from pdf --to md --output ./ --image-export-mode placeholder
```

## Links

By default when you create links in AsciiDoc they are all based on fragment identifiers, eg mysite/page#foo.However, we have a lot of content on different topics and by default the TOC is overwhelming. The Hub site groups topics like this:

```aiignore
Welcome
 └─ Introduction
     ├─ Agentic AI for the JVM
     ├─ As modern as Kotlin, as proven ...
     └─ The Team Behind Embabel
 └─ Overview

Quickstart & Guides
 ├─ Getting Started
 └─ Guides

Reference
 ├─ Flow
 ├─ Steps
 ├─ Domain
 ├─ Configuration
 ├─ Annotations
 ├─ DSL
 ├─ Types
 ├─ Tools
 └─ Prompt Contributors
```

### In this grouping:

* Each group is a separate page on the site, eg hub.emababel.org/intro and hub.embabel.org/reference
* Links inside these separate pages are fragments.

We need a way to for links to work in this format, without breaking anything in normal asciidoc. Therefore:

* The dot (.) char in links represents a slash
* The double underscore (__) represents a #.
* All links are assumed to start with a slash

So to link to a fragment in the reference section of the docs:

* Anchor format: [[reference.flow__topic]]
* Linking back to it: <<reference.flow__topic>>
