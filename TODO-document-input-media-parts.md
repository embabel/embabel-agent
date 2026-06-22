# TODO: Document Input Media Parts

Branch: `document-input-media-parts`

Goal: add generic document/binary input support for PDF, spreadsheets, and related MIME types while preserving existing image APIs.

## Completed

- [x] Confirmed baseline before development.
- [x] Added shared MIME constants:
  - `embabel-agent-common/embabel-agent-ai/src/main/kotlin/com/embabel/common/ai/media/MimeTypes.kt`
- [x] Added MIME helper functions:
  - `embabel-agent-common/embabel-agent-ai/src/main/kotlin/com/embabel/common/ai/media/mimeTypeUtils.kt`
- [x] Added supported document MIME groups:
  - PDF
  - XLSX
  - CSV
  - DOC/DOCX
  - ODT/ODS/ODP
- [x] Added `MediaPart` and `DocumentPart` beside `ImagePart`.
- [x] Replaced Jackson deduction for `ContentPart` with custom MIME-based deserialization.
- [x] Added `ContentPartDeserializer`.
- [x] Added `documentParts` and `mediaParts` accessors on messages.
- [x] Added `UserMessageBuilder.document(...)` overloads.
- [x] Updated Spring AI message conversion to send all `MediaPart`s as Spring AI `Media`.
- [x] Added focused tests for:
  - document deserialization by MIME type
  - OpenDocument spreadsheet MIME type
  - unsupported media MIME rejection
  - mixed text/image/document content parts
  - Spring AI conversion of image + document media

## Current Review Notes

- [ ] User will run build/tests. Codex should not compile or run tests unless explicitly asked.
- [ ] Keep MIME constants centralized in `MimeTypes`.
- [ ] Keep MIME detection helpers in lower-camel-case utility file `mimeTypeUtils.kt`.
- [ ] Avoid spreading extension-to-MIME mapping into builders or API classes.
- [ ] Keep `ContentPartDeserializer` as a sibling of `ContentPart.kt`, not under `chat/support`.
- [ ] Do not introduce synthetic `type` / `kind` fields; `mimeType` is the semantic discriminator.

## Next Implementation Steps

- [ ] Review low-level changes after user build/test feedback.
- [ ] Add higher-level `AgentDocument` or `AgentMedia` model in `MultimodalContent.kt`.
- [ ] Extend `MultimodalContent` to carry documents while preserving `images`.
- [ ] Add builder methods:
  - `document(document: AgentDocument)`
  - `document(mimeType: String, data: ByteArray, filename: String? = null)`
  - `document(file: File)`
  - `document(path: Path)`
- [ ] Add companion helpers:
  - `MultimodalContent.withDocument(...)`
  - `MultimodalContent.withDocuments(...)`
- [ ] Add `PromptRunner.withDocument(...)` APIs.
- [ ] Add stored document plumbing through:
  - `PromptRunner`
  - `PromptExecutionDelegate`
  - `OperationContextDelegate`
  - `DelegatingStreamingPromptRunner`
  - `FakePromptRunner`
- [ ] Generalize image-combining helpers to media-combining helpers where appropriate.
- [ ] Add tests for:
  - `MultimodalContent` document conversion to `DocumentPart`
  - `PromptRunner.withDocument(...)`
  - combining stored images and documents into final `UserMessage`

## Capability Gating Follow-Up

- [ ] Do not block document plumbing on provider capability metadata.
- [ ] Align future gating with #1585 as an input-modality capability.
- [ ] Future gate should answer:
  - model supports document input
  - MIME type is supported
  - size/count limits are respected
