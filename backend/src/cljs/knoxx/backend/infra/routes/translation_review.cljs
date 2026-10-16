(ns knoxx.backend.infra.routes.translation-review
  "Translation approval facade.

  Lists resource translation work, appends candidate-bound split reviews, and
  records whole-output approval. Everything in and out is CLJS data — no
  Fastify handle enters or leaves this namespace. The owning extern adapter is
  `knoxx.backend.extern.fastify.translation-review`.

  Approval is evidence, not an effect. Nothing here materializes anything, and
  the card is explicit about why: an approval may make a publication plan
  admissible, but it must not itself publish. That separation is what lets a
  reviewer accept a translation without also deciding when the bytes go live."
  (:require [knoxx.backend.infra.translation-review-authority :as authority]
            [knoxx.backend.infra.translation-review-inventory :as inventory]
            [knoxx.backend.infra.translation-review-mutations :as mutations]))

(defn ^:async approve-translation!
  "Record approval of one translated revision, or refuse with typed evidence.

   Returns one of:

     {:approval/status :recorded :approval a}   first approval of this output
     {:approval/status :existing :approval a}   already approved, idempotent
     {:approval/refusal r}                      refused, with both sides named

   The request is validated, then the receipt is looked up, then the refusal is
   computed, and only then is anything written. Nothing is persisted on a
   refusal: an approval that was rejected must leave no trace a later read could
   mistake for evidence."
  [{:keys [evidence-store clock authorize-approval! receipt-admissible?
           receipts-transform receipts-snapshot]}
   scope request]
  (await (authority/approve!
          {:evidence-store evidence-store :clock clock
           :authorize-approval! authorize-approval!
           :receipt-admissible? receipt-admissible?
           :receipts-transform receipts-transform :receipts-snapshot receipts-snapshot}
          scope request)))

(defn ^:async reviewable-translations!
  "Resource-derived translation work, left-joined with observed evidence.

   Resource intent is cardinality authority. A missing receipt is a visible
   `:missing` row rather than no row, and an orphan receipt cannot invent work.
   This is the inverse of the old receipt-first projection that reduced eighteen
   desired relations to the only completed candidate."
  [{:keys [evidence-store split-store digest-hex publication-index
           source-revisions receipts-transform receipts-snapshot]} scope]
  (await (inventory/reviewable!
          {:evidence-store evidence-store :split-store split-store
           :digest-hex digest-hex :publication-index publication-index
           :source-revisions source-revisions :receipts-transform receipts-transform
           :receipts-snapshot receipts-snapshot}
          scope)))

(defn ^:async record-split-review!
  "Append one server-attributed split review and refresh its current output.

  The request selects only a candidate set, split, verdict, optional correction,
  and optional evaluation fields. Tenant/project, principal, clock, immutable
  candidate coordinates, and retry identity are all resolved on this side of
  the trust boundary."
  [deps scope request]
  (await (mutations/record-split-review! deps scope request)))

(defn ^:async record-candidate-set-review!
  "Apply one document-level verdict to every split in an exact candidate set.

  The server enumerates the persisted manifest, so a stale or malicious client
  cannot omit members or smuggle split ids from another candidate. Review facts
  are appended in manifest order and the effective document is projected once.
  A partial crash is retry-safe and publication-safe for the reasons documented
  by `append-current-review!`."
  [deps scope request]
  (await (mutations/record-candidate-set-review! deps scope request)))
