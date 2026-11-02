(ns knoxx.backend.law.native-translation-boundary-fixture
  "Byte-exact saved CMS source/candidate evidence from the 2026-10-15 failure.")

;; This is the saved Knoxx CMS readback, not a raw model-provider transcript.
;; The authored exercise contains no credentials or personal/client content.
;; Tenant, user, container and local filesystem coordinates are omitted.
(def provenance
  "Immutable identifiers and byte hashes of the observed native failure."
  {:observed-at "2026-10-15T07:40:21.651Z"
   :source-revision "sha256-b9dabbf302d82857fc84387a72a8ee1005f0f134b79bc7ea0de7fdb1fb87b634"
   :assembled-candidate-sha256 "00e0e9c99c0dcd68dfde32c83a4077c8f5f891fcfee10e21bf30a1411ed41d51"
   :saved-http-response-sha256 "ffc1a70ef1117077e0cf09bcc89189083463d3d16beea7cb40c2478f56a0d4e3"
   :candidate-set-id "translation-candidate-set-e7da160181982fc4c92cfdaa2d40fb314f44a882a3a3ba3295667ec2d9be1daa"
   :manifest-id "translation-split-manifest-05e88ef6b8653b98a71891dfca674495a44b818962c3b3b1171275c2e8653638"
   :raw-provider-transcript? false})

(def observed-pairs
  "All three source/candidate pairs, with their original whitespace intact."
  [
   {:split-index 0
    :source-text "This private Open Hax exercise uses Knoxx to prepare one English source document for a Spanish translation candidate. The document describes a small internal review journey for the Promethean workspace. It contains three complete paragraphs so that the translation can retain the source structure and every sentence. No paragraph is an instruction to publish content or grant approval.\n\n"
    :candidate-text "Este ejercicio privado de Open Hax utiliza Knoxx para preparar un documento fuente en inglés para un candidato de traducción al español. El documento describe un pequeño viaje de revisión interna para el espacio de trabajo Promethean. Contiene tres párrafos completos para que la traducción pueda mantener la estructura de la fuente y cada oración. Ningún párrafo es una instrucción para publicar contenido o conceder aprobación."
    :candidate-digest "d20be34be13c7dab176b4673778805e606deff8fbe6876451d86b5b397e7b189"}
   {:split-index 1
    :source-text "The required terminology is Open Hax, Knoxx, and Promethean. Preserve those three names exactly while translating the surrounding English into natural Spanish. Keep the meaning of source revision, candidate revision, and human review clear. The reviewer must be able to compare the complete English source with the complete Spanish candidate, including this paragraph and the next one.\n\n"
    :candidate-text "La terminología requerida es Open Hax, Knoxx y Promethean. Preserve esos tres nombres exactamente mientras traduces el inglés circundante a español natural. Mantén el significado de revisión de fuente, revisión de candidato y revisión humana claro. El revisor debe poder comparar la fuente en inglés completa con el candidato en español completo, incluyendo este párrafo y el siguiente."
    :candidate-digest "a9efa827e63553e47a5b7fb67b18f58f21ee928c0f178a74fb8151324961b291"}
   {:split-index 2
    :source-text "After the candidate is generated, retain its source identity, paragraph boundaries, and the records that connect it to this document. A completed candidate remains available for a later independent human review. This exercise ends when Knoxx can read back the genuine Spanish result and its persisted lineage. Approval, reconciliation, and publication are separate actions and do not happen during this exercise."
    :candidate-text "Después de que se genere el candidato, conserva su identidad de fuente, límites de párrafo y los registros que lo conectan con este documento. Un candidato completado permanece disponible para una revisión humana independiente posterior. La aprobación, conciliación y publicación son acciones separadas y no ocurren durante este ejercicio."
    :candidate-digest "4f62365b025c507d9f185cc1926b534f813aa70b2fc55d7db20d06f3de8634e0"}])
