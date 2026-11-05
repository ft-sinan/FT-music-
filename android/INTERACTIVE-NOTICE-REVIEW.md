# Upstream interactive-notice review

This is a technical record, not a legal opinion. Before updating the visible FT Music branding,
the original Echo Music snapshot at `c0de840532aadc36a84475b63291fbc6452a1668` was reviewed:

* Its Welcome and About screens were inspected directly. Neither contains an explicit copyright
  notice together with a no-warranty statement.
* The snapshot's app Kotlin source and locale string resources were searched for copyright,
  warranty and GPL/legal-notice text and references to its license resources. The GPL strings
  found in resources were not referenced by the app's Kotlin UI in that snapshot.
* GPLv3's interactive-notice exception and its separate source/distribution notice obligations
  were considered. This inspection does not establish a legal conclusion for every dependency or
  future upstream revision.

The original source notices remain in the repository. `LICENSE`, `UPSTREAM.md`, and distribution
notes identify the upstream project and its license. FT Music's About screen links to the source,
identifies GPLv3, and states that there is no warranty. Preserve full corresponding source and all
applicable notices with every distributed build; obtain legal advice if the obligations are unclear.
